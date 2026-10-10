"""Real Windows/BlueZ BLE smoke test. No credentials, claiming, ACK or auth bypass.

Match the expected UUID from this board's USB boot log before sending test writes.
Results establish public GATT and rejection/lifecycle behavior, never authorized sync.
"""
import argparse
import asyncio
import json
import struct
import uuid
from pathlib import Path
from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError

SERVICE = '6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b6c'

def characteristic(index):
    return f'6f4a2c1e-8b3d-4e5f-9a0c-1d2e3f4a5b{index:02x}'

def frame(opcode, payload=b''):
    return struct.pack('<BBH', 1, opcode, len(payload)) + payload

def unpack(data, opcode=None):
    data = bytes(data)
    if len(data) < 4:
        raise AssertionError('short frame')
    version, op, length = struct.unpack('<BBH', data[:4])
    assert version == 1 and length == len(data) - 4, 'invalid frame'
    if opcode is not None:
        assert op == opcode, f'expected opcode {opcode}, observed {op}'
    return op, data[4:]

async def hello(client):
    _, payload = unpack(await client.read_gatt_char(characteristic(1)), 2)
    assert len(payload) >= 38
    return str(uuid.UUID(bytes=payload[3:19])), payload

async def rejected_read(client, index):
    try:
        response = await client.read_gatt_char(characteristic(index))
    except BleakError:
        return 'ATT rejected'
    op, payload = unpack(response)
    assert op == 255 and payload[0] in (2, 10, 14), 'protected value exposed'
    return 'protocol rejected'

async def rejected_write(client, index, request):
    try:
        await client.write_gatt_char(characteristic(index), request, response=True)
    except BleakError:
        return 'ATT rejected'
    op, payload = unpack(await client.read_gatt_char(characteristic(index)))
    assert op == 255 and payload[0] in (2, 10, 14), 'unauthorized write accepted'
    return 'protocol rejected'

async def run(args):
    results = {'expected_node': args.node_id, 'authorized_sync': 'UNVERIFIED: trusted UTC and deployed issuer/claim prerequisites missing'}
    found = await BleakScanner.discover(timeout=12, return_adv=True)
    candidates = [(device, adv) for device, adv in found.values() if SERVICE in adv.service_uuids]
    results['discovered_service_devices'] = len(candidates)
    if not args.node_id:
        for device, adv in candidates:
            print(f'{device.address} {adv.local_name} service={SERVICE} data={adv.service_data.get(SERVICE, b"").hex()}')
        return results
    selected = None
    for device, adv in candidates:
        async with BleakClient(device, timeout=15, winrt={'use_cached_services': False}) as client:
            node_id, _ = await hello(client)
            if node_id == args.node_id:
                selected = device
                results['advertisement'] = {'service': SERVICE, 'name': adv.local_name,
                    'service_data': adv.service_data.get(SERVICE, b'').hex()}
                data = adv.service_data.get(SERVICE)
                if data is not None:
                    assert len(data) == 4 and data[0] == 1 and data[1] & ~3 == 0 and data[2:] == b'\0\0'
                break
    assert selected, 'expected physical Node ID not found; no test writes performed'
    previous_challenge = None
    for cycle in range(2):
        async with BleakClient(selected, timeout=15, winrt={'use_cached_services': False}) as client:
            node_id, public = await hello(client)
            assert node_id == args.node_id
            service = client.services.get_service(SERVICE)
            chars = {c.uuid: set(c.properties) for c in service.characteristics}
            assert set(chars) == {characteristic(i) for i in range(1, 12)}, 'missing/unexpected characteristic'
            for index in range(1, 12):
                assert 'read' in chars[characteristic(index)]
                assert ('write' in chars[characteristic(index)]) == (index in (4, 5, 7, 8, 9, 11))
            assert 'notify' in chars[characteristic(7)]
            owner = await client.read_gatt_char(characteristic(2)); unpack(owner, 4)
            challenge = await client.read_gatt_char(characteristic(3))
            _, nonce = unpack(challenge, 6)
            assert len(nonce) == 32 and nonce != previous_challenge, 'nonce reused on reconnect'
            previous_challenge = nonce
            rejected = {}
            for index in (5, 6, 7, 8, 9):
                rejected[str(index)] = await rejected_read(client, index)
            requests = {5: frame(9, bytes(32)), 7: frame(11, struct.pack('<QH', 1, 1)),
                8: frame(13, struct.pack('<Q', 1)), 9: frame(15, struct.pack('<Q', 1790899200000))}
            for index, request in requests.items():
                rejected[f'write-{index}'] = await rejected_write(client, index, request)
            # Real fragmented Auth transport, invalid credential, then rejection.
            request = frame(7, struct.pack('<H', 7) + b'invalid' + bytes(64))
            for offset in range(0, len(request), 12):
                envelope = b'MM' + bytes((1, int(offset == 0))) + struct.pack('<HH', len(request), offset) + request[offset:offset+12]
                await client.write_gatt_char(characteristic(4), envelope, response=True)
            _, auth = unpack(await client.read_gatt_char(characteristic(4)), 8)
            assert auth[0] == 0, 'invalid credential authorized'
            await rejected_read(client, 6)
            # Receipt has no physical claim proof/trusted time; reject, never commit.
            await client.write_gatt_char(characteristic(11), frame(23, struct.pack('<H', 7) + b'invalid'), response=True)
            _, receipt = unpack(await client.read_gatt_char(characteristic(11)), 255)
            assert receipt[0] == 14
            results[f'connection_{cycle+1}'] = {'node_id': node_id, 'mtu': client.mtu_size,
                'characteristics': 11, 'clock': public[-1], 'protected_operations': rejected,
                'invalid_fragmented_auth': 'rejected', 'invalid_claim_receipt': 'rejected'}
        await asyncio.sleep(0.5)
    results['result'] = 'PASS: actual BLE discovery, GATT, rejection and reconnect checks'
    return results

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--node-id', type=lambda v: str(uuid.UUID(v)))
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = asyncio.run(run(args))
    text = json.dumps(result, indent=2)
    print(text)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + '\n', encoding='utf-8')

if __name__ == '__main__':
    main()
