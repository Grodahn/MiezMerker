// Public-only GATT probe (issue #8).
//
// Reads HelloPublic + OwnerResponse before authorization. Exposes no
// observations, chip IDs or pending counts. Foreign organizations stop here
// and display only the public owner hint.

import {
  decodeFrame, decodeHelloPublic, decodeOwnerResponse, encodeFrame,
  encodeHelloRequest, CAP_ALL_V1, Opcode, PROTOCOL_VERSION, type OwnerInfo,
} from './ble-codec';
import type { NodeTransport } from '../platform/node-transport';

export interface PublicNodeInfo {
  nodeId: string;
  incarnation: string;
  claimState: number;
  serverVer: number;
  owner: OwnerInfo | null;
}

export class PublicProbeTransport {
  constructor(private readonly transport: NodeTransport) {}

  async readPublicHello(): Promise<PublicNodeInfo> {
    try {
      await this.transport.connect();
      const helloResp = await this.roundTrip(encodeFrame({
        version: PROTOCOL_VERSION, opcode: Opcode.HelloRequest,
        payload: encodeHelloRequest({ clientVer: PROTOCOL_VERSION, caps: CAP_ALL_V1 }),
      }));
      const helloFrame = decodeFrame(helloResp);
      if (!helloFrame || helloFrame.opcode !== Opcode.HelloPublic) throw new Error('hello failed');
      const hello = decodeHelloPublic(helloFrame.payload);
      if (!hello) throw new Error('hello failed');
      if (helloFrame.version !== PROTOCOL_VERSION || hello.serverVer !== PROTOCOL_VERSION ||
          (hello.caps & CAP_ALL_V1) !== CAP_ALL_V1) throw new Error('Protokoll inkompatibel.');
      const ownerResp = await this.roundTrip(encodeFrame({
        version: PROTOCOL_VERSION, opcode: Opcode.OwnerRequest, payload: new Uint8Array(),
      }));
      const ownerFrame = decodeFrame(ownerResp);
      const owner = ownerFrame && ownerFrame.opcode === Opcode.OwnerResponse
        ? decodeOwnerResponse(ownerFrame.payload) : null;
      if (!owner || ownerFrame?.version !== PROTOCOL_VERSION || owner.nodeId !== hello.nodeId ||
          owner.claimState !== hello.claimState) throw new Error('Ungültige öffentliche Node-Identität.');
      return {
        nodeId: hello.nodeId, incarnation: hello.incarnation,
        claimState: hello.claimState, serverVer: hello.serverVer, owner,
      };
    } finally {
      await this.transport.disconnect();
    }
  }

  private async roundTrip(frame: Uint8Array): Promise<Uint8Array> {
    await this.transport.write(frame);
    return this.transport.read();
  }
}
