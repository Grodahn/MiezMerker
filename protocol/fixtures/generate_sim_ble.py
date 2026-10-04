#!/usr/bin/env python3
"""Generate deterministic sim-BLE fixtures for issue #7 (Muse Spark 1.3).

Produces protocol/fixtures/sim-ble-v1.json with canonical ES256 JWTs,
challenge/proof vectors, claim receipts/advertisements and node session
proofs. All keys are fresh test-only P-256 (never production). No private
keys are committed: this file only outputs public material + signatures.

Reusable by #8 PWA tests, #13 e2e and backend integration tests.
"""
import base64
import hashlib
import json
import sys
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import (
    decode_dss_signature,
    encode_dss_signature,
)

KID = "miezmerker-issuer-v1"
ISS = "miezmerker"

# Fixed deterministic UUIDs (valid v4, lowercase).
ORG_A = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a"
ORG_B = "0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b"
USER_MEMBER = "1a1a1a1a-1a1a-4a1a-8a1a-1a1a1a1a1a1a"
USER_ADMIN = "1b1b1b1b-1b1b-4b1b-8b1b-1b1b1b1b1b1b"
USER_FOREIGN = "1c1c1c1c-1c1c-4c1c-8c1c-1c1c1c1c1c1c"
DEV_MEMBER = "2a2a2a2a-2a2a-4a2a-8a2a-2a2a2a2a2a2a"
DEV_ADMIN = "2b2b2b2b-2b2b-4b2b-8b2b-2b2b2b2b2b2b"
DEV_FOREIGN = "2c2c2c2c-2c2c-4c2c-8c2c-2c2c2c2c2c2c"
NODE_MAIN = "3a3a3a3a-3a3a-4a3a-8a3a-3a3a3a3a3a3a"

IAT = 1790899200  # 2026-10-02T00:00:00Z
EXP = 1917129600  # 2030-10-02T00:00:00Z
TRUSTED_NOW = 1790899300  # scenario trusted time (IAT+100)
EXPIRED_IAT = 1759363200
EXPIRED_EXP = 1759449600
CLAIM_TS = 1790899200000  # fixed claim advertisement millis
STALE_TS = 1759363200000

FIXED_CHALLENGE = bytes(range(32))
ALT_CHALLENGE = bytes([0x2A] * 32)
FIXED_PWA_NONCE = bytes([0xB0] * 32)


def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def fixed32(n: int) -> bytes:
    return n.to_bytes(32, "big")


def pub_xy(key) -> tuple[str, str]:
    nums = key.public_key().public_numbers()
    return b64u(fixed32(nums.x)), b64u(fixed32(nums.y))


def fingerprint(x_b64u: str, y_b64u: str) -> str:
    x = base64.urlsafe_b64decode(x_b64u + "==")
    y = base64.urlsafe_b64decode(y_b64u + "==")
    return b64u(hashlib.sha256(b"\x04" + x + y).digest())


def sign_raw(priv, msg: bytes) -> bytes:
    der = priv.sign(msg, ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der)
    return fixed32(r) + fixed32(s)


def sign_jwt(priv, header: dict, payload: dict) -> str:
    hb = b64u(json.dumps(header, separators=(",", ":")).encode())
    pb = b64u(json.dumps(payload, separators=(",", ":")).encode())
    signing_input = f"{hb}.{pb}".encode()
    raw = sign_raw(priv, signing_input)
    return f"{hb}.{pb}.{b64u(raw)}"


def main() -> None:
    issuer = ec.generate_private_key(ec.SECP256R1())
    dev_m = ec.generate_private_key(ec.SECP256R1())
    dev_a = ec.generate_private_key(ec.SECP256R1())
    dev_f = ec.generate_private_key(ec.SECP256R1())
    node = ec.generate_private_key(ec.SECP256R1())

    ix, iy = pub_xy(issuer)
    mx, my = pub_xy(dev_m)
    ax, ay = pub_xy(dev_a)
    fx, fy = pub_xy(dev_f)
    nx, ny = pub_xy(node)
    ifp = fingerprint(ix, iy)
    mfp = fingerprint(mx, my)
    afp = fingerprint(ax, ay)
    ffp = fingerprint(fx, fy)
    nfp = fingerprint(nx, ny)

    header = {"kid": KID, "alg": "ES256"}

    def offline(sub, org, slug, dev, dx, dy, dfp, role, iat, exp, jti):
        return sign_jwt(
            issuer,
            header,
            {
                "iss": ISS,
                "sub": sub,
                "jti": jti,
                "iat": iat,
                "exp": exp,
                "ver": 1,
                "kind": "offline",
                "org": org,
                "org_slug": slug,
                "dev": dev,
                "dpk_x": dx,
                "dpk_y": dy,
                "dpf": dfp,
                "role": role,
                "scope": ["node:sync"],
            },
        )

    member_jwt = offline(USER_MEMBER, ORG_A, "sim-org-a", DEV_MEMBER, mx, my, mfp,
                         "MEMBER", IAT, EXP, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    admin_jwt = offline(USER_ADMIN, ORG_A, "sim-org-a", DEV_ADMIN, ax, ay, afp,
                        "ADMIN", IAT, EXP, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    foreign_jwt = offline(USER_FOREIGN, ORG_B, "sim-org-b", DEV_FOREIGN, fx, fy, ffp,
                          "MEMBER", IAT, EXP, "cccccccc-cccc-4ccc-8ccc-cccccccccccc")
    expired_jwt = offline(USER_MEMBER, ORG_A, "sim-org-a", DEV_MEMBER, mx, my, mfp,
                          "MEMBER", EXPIRED_IAT, EXPIRED_EXP,
                          "dddddddd-dddd-4ddd-8ddd-dddddddddddd")

    def claim_receipt(node_id, org, slug, name, contact, ndx, ndy, ndp, jti):
        return sign_jwt(
            issuer,
            header,
            {
                "iss": ISS,
                "sub": node_id,
                "jti": jti,
                "iat": IAT,
                "exp": IAT + 10 * 365 * 24 * 3600,
                "ver": 1,
                "kind": "claim",
                "org": org,
                "org_slug": slug,
                "org_name": name,
                "contact": contact,
                "ndpk_x": ndx,
                "ndpk_y": ndy,
                "ndpf": ndp,
                "ipk_x": ix,
                "ipk_y": iy,
            },
        )

    receipt_a = claim_receipt(NODE_MAIN, ORG_A, "sim-org-a", "Sim Org A",
                              "help@sim-a.example", nx, ny, nfp,
                              "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
    receipt_b = claim_receipt(NODE_MAIN, ORG_B, "sim-org-b", "Sim Org B",
                              "help@sim-b.example", nx, ny, nfp,
                              "ffffffff-ffff-4fff-8fff-ffffffffffff")

    proof_m = b64u(sign_raw(dev_m, FIXED_CHALLENGE))
    proof_a = b64u(sign_raw(dev_a, FIXED_CHALLENGE))
    proof_f = b64u(sign_raw(dev_f, FIXED_CHALLENGE))

    claim_msg = (f"MM-CLAIM-v1\n{NODE_MAIN}\n{nx}\n{ny}\n{CLAIM_TS}\nclaim-mode").encode()
    claim_sig = b64u(sign_raw(node, claim_msg))
    stale_msg = (f"MM-CLAIM-v1\n{NODE_MAIN}\n{nx}\n{ny}\n{STALE_TS}\nclaim-mode").encode()
    stale_sig = b64u(sign_raw(node, stale_msg))

    sess_msg = f"MM-NODE-SESSION-v1\n{NODE_MAIN}\n{b64u(FIXED_PWA_NONCE)}".encode()
    sess_sig = b64u(sign_raw(node, sess_msg))

    root = {
        "format": "miezmerker-sim-ble-v1",
        "description": "Deterministic BLE/security fixtures for issue #7 simulator "
                       "(Muse Spark 1.3). Test-only keys; never production. Reusable by "
                       "#8 PWA tests, #13 e2e and backend integration tests. ECDSA "
                       "signatures are single valid examples over fixed challenges/nonces.",
        "algorithms": {
            "credential": "JWS compact, ES256, kid=miezmerker-issuer-v1",
            "challenge_signature": "ECDSA P-256/SHA-256 over raw 32B challenge, raw 64B r||s base64url",
            "fingerprint": "base64url(SHA-256(0x04 || x || y))",
            "claim_message": "ASCII 'MM-CLAIM-v1\\n{nodeId}\\n{x}\\n{y}\\n{timestampMillis}\\nclaim-mode'",
            "session_message": "ASCII 'MM-NODE-SESSION-v1\\n{nodeId}\\n{base64url_32B_nonce}'",
        },
        "trusted_now_s": TRUSTED_NOW,
        "issuer_public_key": {"kid": KID, "x": ix, "y": iy, "fingerprint": ifp},
        "organization_a": {"id": ORG_A, "slug": "sim-org-a", "name": "Sim Org A",
                           "contact": "help@sim-a.example"},
        "organization_b": {"id": ORG_B, "slug": "sim-org-b", "name": "Sim Org B",
                           "contact": "help@sim-b.example"},
        "user_member_a": USER_MEMBER,
        "user_admin_a": USER_ADMIN,
        "user_foreign_b": USER_FOREIGN,
        "device_member": {"id": DEV_MEMBER, "x": mx, "y": my, "fingerprint": mfp},
        "device_admin": {"id": DEV_ADMIN, "x": ax, "y": ay, "fingerprint": afp},
        "device_foreign": {"id": DEV_FOREIGN, "x": fx, "y": fy, "fingerprint": ffp},
        "node_main": {"id": NODE_MAIN, "x": nx, "y": ny, "fingerprint": nfp},
        "credentials": {
            "member_valid_jwt": member_jwt,
            "admin_valid_jwt": admin_jwt,
            "foreign_valid_jwt": foreign_jwt,
            "expired_jwt": expired_jwt,
        },
        "challenges": {
            "fixed_hex": FIXED_CHALLENGE.hex(),
            "alt_hex": ALT_CHALLENGE.hex(),
        },
        "proofs": {
            "member_fixed_b64u": proof_m,
            "admin_fixed_b64u": proof_a,
            "foreign_fixed_b64u": proof_f,
        },
        "claim_receipts": {
            "orgA_nodeMain_jwt": receipt_a,
            "orgB_nodeMain_jwt": receipt_b,
        },
        "claim_advertisement": {
            "timestamp_millis": CLAIM_TS,
            "message_ascii": claim_msg.decode(),
            "signature_b64u": claim_sig,
            "stale_timestamp_millis": STALE_TS,
            "stale_message_ascii": stale_msg.decode(),
            "stale_signature_b64u": stale_sig,
        },
        "node_session_proof": {
            "fixed_nonce_hex": FIXED_PWA_NONCE.hex(),
            "message_ascii": sess_msg.decode(),
            "signature_b64u": sess_sig,
        },
    }
    out = sys.argv[1] if len(sys.argv) > 1 else "protocol/fixtures/sim-ble-v1.json"
    with open(out, "w", encoding="utf-8") as f:
        json.dump(root, f, indent=2)
        f.write("\n")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
