"""Explicit regeneration only. Requires cryptography; never run by normal tests.
Public test scalars 1/2, never production keys. Verification is deterministic;
ECDSA signatures are fixed published examples, not reproducible sign outputs.
"""
import base64
import hashlib
import json
import struct
import uuid
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils

def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

def sign(key, message):
    r, s = utils.decode_dss_signature(key.sign(message, ec.ECDSA(hashes.SHA256())))
    return b64(r.to_bytes(32, "big") + s.to_bytes(32, "big"))

def point(key):
    return key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)

issuer = ec.derive_private_key(1, ec.SECP256R1())
node = ec.derive_private_key(2, ec.SECP256R1())
node_id = "44444444-4444-4444-8444-444444444444"
incarnation = "55555555-5555-4555-8555-555555555555"
anchor = "66666666-6666-4666-8666-666666666666"
nonce = bytes(range(32))
pub = point(node)
fp = b64(hashlib.sha256(pub).digest())
prefix = struct.pack("<H", 1) + uuid.UUID(node_id).bytes + uuid.UUID(incarnation).bytes + struct.pack("<I", 42)
challenge_bytes = b"MM-NODE-TIME-CHALLENGE-v1\n" + prefix + struct.pack("<Q", 1000) + nonce + pub
challenge = dict(nodeId=node_id, incarnation=incarnation, bootCounter=42,
    challengeNonce=b64(nonce), challengeUptimeMs=1000, devicePublicKey=b64(pub),
    devicePublicKeyFingerprint=fp, deviceChallengeSignature=sign(node, challenge_bytes), version=1)
payload = dict(iss="miezmerker", kid="miezmerker-issuer-v1", kind="node-time-bootstrap", ver=1,
    node=node_id, incarnation=incarnation, boot=42, nonce=b64(nonce), dpf=fp,
    utc_ms=1791748800123, jti="77777777-7777-4777-8777-777777777777")
def jws(payload, header=None):
    header = header or dict(alg="ES256", kid="miezmerker-issuer-v1")
    signing = ".".join(b64(json.dumps(v, separators=(",", ":")).encode()) for v in [header, payload])
    return signing + "." + sign(issuer, signing.encode())
proof = jws(payload)
nonce_digest = hashlib.sha256(nonce).digest()
proof_digest = hashlib.sha256(proof.encode()).digest()
accept_bytes = b"MM-NODE-TIME-ACCEPT-v1\n" + prefix + nonce_digest + proof_digest + struct.pack("<Q", 1300) + uuid.UUID(anchor).bytes
acceptance = dict(nodeId=node_id, incarnation=incarnation, bootCounter=42, proofDigest=b64(proof_digest),
    challengeNonceDigest=b64(nonce_digest), acceptedUptimeMs=1300, anchorId=anchor, signature=sign(node, accept_bytes), version=1)
negatives = {f"wrong_{field}": jws({**payload, field: val}) for field, val in
    [("iss", "attacker"), ("kind", "offline"), ("kid", "other"), ("node", incarnation),
     ("incarnation", node_id), ("boot", 43), ("nonce", b64(bytes(32))), ("ver", 2)]}
negatives["overflow_boot"] = jws({**payload, "boot": (1 << 64) + 42})
negatives["overflow_version"] = jws({**payload, "ver": (1 << 64) + 1})
negatives["overflow_utc"] = jws({**payload, "utc_ms": 1 << 64})
duplicate = json.dumps(payload, separators=(",", ":")).replace('"ver":1', '"ver":1,"ver":1')
signing = b64(json.dumps(dict(alg="ES256", kid=payload["kid"]), separators=(",", ":")).encode()) + "." + b64(duplicate.encode())
negatives["duplicate_version"] = signing + "." + sign(issuer, signing.encode())
negatives["wrong_header_kid"] = jws(payload, dict(alg="ES256", kid="other"))
negatives["wrong_algorithm"] = jws(payload, dict(alg="HS256", kid="miezmerker-issuer-v1"))
negatives["tampered"] = proof[:-86] + b64(bytes(64))
fixture = dict(format="miezmerker-node-time-v1", expectedUtcMs=payload["utc_ms"],
    issuerPublicKey=dict(x=b64(point(issuer)[1:33]), y=b64(point(issuer)[33:65]), kid=payload["kid"]),
    challenge=challenge, challengeSigningHex=challenge_bytes.hex(), proof=proof,
    acceptance=acceptance, acceptanceSigningHex=accept_bytes.hex(), negativeProofs=negatives)
Path(__file__).with_name("node-time-v1.json").write_text(json.dumps(fixture, indent=2) + "\n", encoding="utf-8")
