#include "miezmerker/node_identity.hpp"
#include <cassert>
#include <cstring>
#include <iostream>
#include <map>
#include <vector>

struct TestRandom final : miezmerker::RandomSource {
    std::size_t counter{0};
    void random_bytes(std::span<std::byte> out) override {
        for (auto& b : out) {
            b = static_cast<std::byte>((counter++ * 31 + 7) & 0xff);
        }
    }
};

struct MemoryIdentityStore final : miezmerker::NodeIdentityStore {
    std::map<std::string, std::vector<std::byte>> data;
    bool fail_save{false};
    bool load(miezmerker::NodeIdentity& identity) override {
        auto it = data.find("identity");
        if (it == data.end() || it->second.size() != sizeof(miezmerker::NodeIdentity)) {
            return false;
        }
        std::memcpy(&identity, it->second.data(), sizeof(miezmerker::NodeIdentity));
        return true;
    }
    bool save(const miezmerker::NodeIdentity& identity) override {
        if (fail_save) return false;
        std::vector<std::byte> bytes(sizeof(miezmerker::NodeIdentity));
        std::memcpy(bytes.data(), &identity, sizeof(miezmerker::NodeIdentity));
        data["identity"] = std::move(bytes);
        return true;
    }
    bool clear() override {
        data.clear();
        return true;
    }
};

struct FakeClaimMode final : miezmerker::ClaimMode {
    bool claim_active{false};
    bool active() const override { return claim_active; }
};

static bool identity_equal(const miezmerker::NodeIdentity& a, const miezmerker::NodeIdentity& b) {
    return a.node_id == b.node_id && a.public_key == b.public_key
        && a.private_key == b.private_key && a.sequence == b.sequence;
}

int main() {
    // First boot provisions a stable identity.
    {
        MemoryIdentityStore store;
        TestRandom random;
        FakeClaimMode claim;
        miezmerker::NodeIdentityManager mgr(store, claim, random);
        assert(mgr.initialize());
        assert(mgr.ready());
        auto id = mgr.identity();
        // UUIDv4 shape.
        assert((static_cast<std::uint8_t>(id.node_id[6]) & 0xf0) == 0x40);
        assert((static_cast<std::uint8_t>(id.node_id[8]) & 0xc0) == 0x80);
        assert(id.public_key[0] == std::byte{0x04});
        assert(id.sequence == 0);

        // Reboot keeps identity and sequence.
        miezmerker::NodeIdentityManager mgr2(store, claim, random);
        assert(mgr2.initialize());
        assert(identity_equal(id, mgr2.identity()));
        assert(mgr2.next_sequence() == 1);
        assert(mgr2.next_sequence() == 2);

        miezmerker::NodeIdentityManager mgr3(store, claim, random);
        assert(mgr3.initialize());
        assert(mgr3.identity().sequence == 2);
        assert(identity_equal(id, mgr3.identity()));
    }

    // Factory reset creates a new identity and a new sequence lifetime.
    {
        MemoryIdentityStore store;
        TestRandom random;
        FakeClaimMode claim;
        miezmerker::NodeIdentityManager mgr(store, claim, random);
        assert(mgr.initialize());
        auto old_id = mgr.identity();
        assert(mgr.next_sequence() == 1);
        assert(mgr.factory_reset());
        auto new_id = mgr.identity();
        assert(new_id.node_id != old_id.node_id);
        assert(new_id.public_key != old_id.public_key);
        assert(new_id.private_key != old_id.private_key);
        assert(new_id.sequence == 0);
        // Old identity is gone from the store: a reload provisions yet another one.
        miezmerker::NodeIdentityManager mgr2(store, claim, random);
        assert(mgr2.initialize());
        assert(mgr2.identity().node_id != new_id.node_id);
    }

    // Claim mode port: fake reports inactive by default.
    {
        MemoryIdentityStore store;
        TestRandom random;
        FakeClaimMode claim;
        miezmerker::NodeIdentityManager mgr(store, claim, random);
        assert(mgr.initialize());
        assert(!mgr.in_claim_mode());
        claim.claim_active = true;
        assert(mgr.in_claim_mode());
    }

    std::cout << "Node identity: stable across reboot, new after factory reset, claim-mode port\n";
}
