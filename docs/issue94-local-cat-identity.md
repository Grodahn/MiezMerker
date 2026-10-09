# Organization-local cat identity (#94)

Issue #94 verifies the existing model against [ADR 0016](adr/0016-cross-organization-sharing.md),
decision 2. No production changes, migration, global Cat entity or sharing API are needed.

## Existing guarantees and verification

| Requirement | Existing implementation | Focused regression |
| --- | --- | --- |
| Independent names, status and notes for the same chip in A/B | `Cat`, `CatService`, `CatRepository`; V6 `uq_cat_org_chip` | `Issue9IngestTest.localCatProfilesAndNormalizedConflictsRemainIndependent` |
| Same-org duplicates conflict, including chip updates and concurrent requests | `CatService.checkDuplicate` and `saveAndFlush` with HTTP 409; database unique constraint | Above test, `concurrentNormalizedCatRegistrationReturnsOneConflict`, `databaseUniquenessProtectsConcurrentWritersAfterBothPrechecks` |
| Unknown observations never create managed profiles; late registration retains history | Ingest stores `RawObservation` only; `CatHistoryService` reads persisted visits by organization/chip | Extended `Issue10VisitsTest.catRelationIsOptionalSnapshot`; existing `AdminCatsTest.claimingChipReusesPersistedHistoryWithoutRecomputingOrLeakingOtherTenant` |
| Direct UUID and chip access cannot expose foreign profiles/history | Scoped repository/query predicates and `TenantService.requireActive` | Profile test above; existing `Issue9IngestTest.organizationACannotReadOrganizationBResources`, `AdminCatsTest.listFieldsAndForeignIdsAreScoped` and visit isolation tests |
| Multi-membership accounts use an explicit authorized context | REST organization path/query is the explicit context, validated against ACTIVE membership; Admin uses validated session context | Profile test above; `AdminCatsTest.organizationSwitchHasNoStaleDataAndRejectsOldForms` |
| String chips, leading zeros and consistent matching | Existing normalization and `VARCHAR(64)` columns | Profile test and late-registration test above, existing normalization-overflow test |

`CatView` and the generated `app/src/api/generated.ts` already expose the local
Cat UUID, owning organization, chip, name, status and notes. Their contracts remain
unchanged. Photo references and import endpoints do not exist yet; these tests do
not imply those features have been implemented.

## Normalization policy (unchanged)

Treat chips as opaque strings: Java `String.trim()` followed by
`toUpperCase(Locale.ROOT)`. Leading zeros, internal whitespace and punctuation
are retained. Do not parse chips as numbers or introduce additional digit-only,
separator-removal or Unicode-whitespace normalization. Validate the normalized
result as nonempty and at most 64 characters; case conversion can expand length.

Cat construction/update (`Cat.normalizeChipId`) and observation construction/retry
comparison (`RawObservation.normalizeChipId`) use this same policy. REST/Admin
filters normalize input before querying. Derived visits copy the canonical chip
from persisted observations; activity and history match on `(organization_id,
chip_id)`. `CatHistoryService` receives the stored canonical Cat chip. Its optional
`DerivedVisit.cat` relation is a recompute-time snapshot, not the history identity.
Late registration does not rewrite raw rows or visit snapshots; history is already
available through organization/chip matching without recomputing visits.

Future import writers must follow the same normalization, validation and database
uniqueness rules, surface conflicts, and never silently merge or overwrite profiles.
This issue changes neither normalization nor the unique constraint, so existing
stored chips are left untouched. Any future normalization change requires an
explicit audit of legacy noncanonical values and collisions within each organization
using the exact proposed Java policy, before a forward-only migration is considered.
No deployment database was rewritten or presumed collision-free by this verification.

## Security and compatibility

The authenticated user's ACTIVE membership, active organization and active account
are checked server-side. A REST organization ID selects context but is not proof of
authorization. Admin context comes from its session; stale form organization IDs
are rejected after a switch. Existing Spring Security, CSRF, roles and sessions are
unchanged. Knowing a foreign UUID or chip supplies no authority.

Tests use the existing H2 default / `TEST_DB_URL` PostgreSQL configuration. CI runs
the complete PostgreSQL regression suite. Run locally with the compact runner:

```text
python scripts/test.py backend --test Issue9IngestTest
python scripts/test.py backend --test 'Issue10VisitsTest#catRelationIsOptionalSnapshot'
python scripts/test.py backend --domain ingest visits
```

Cross-organization grants/projections (#96/#97), global authorization (#91), media
(#95), visit UI (#100), BLE and aggregation behavior remain outside this issue.
