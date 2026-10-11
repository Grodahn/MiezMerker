#pragma once
#include "miezmerker/core.hpp"
#include <cstdlib>
inline void claim_capture_fixture(miezmerker::Core& core, miezmerker::DeviceIdentityStore& store) {
    miezmerker::IdentityRecord id;
    if (store.load(id) != miezmerker::IdentityLoadResult::OK) std::abort();
    id.claim_state = miezmerker::ClaimState::CLAIMED;
    if (!store.store(id) || !core.refresh_identity()) std::abort();
}
