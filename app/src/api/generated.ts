// Generated from backend OpenAPI. Do not edit.
export interface paths {
    "/api/v1/auth/csrf": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Expose the CSRF token for SPA login flows */
        get: operations["getCsrf"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/login": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Email/password login, establishes a server-side session */
        post: operations["login"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/logout": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Invalidate the current server-side session */
        post: operations["logout"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/session": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Current session with user and memberships */
        get: operations["getSession"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/credentials/issuer": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Backend trust anchor for offline node verification (public key only) */
        get: operations["getCredentialIssuer"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/devices": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List my AppDevices */
        get: operations["listDevices"];
        put?: never;
        /** Register the current user's AppDevice public key (P-256) */
        post: operations["registerDevice"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/devices/{deviceId}/credentials": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Issue a signed, time-limited, organization-bound offline credential for my AppDevice (blocked when membership/device is not ACTIVE) */
        post: operations["issueOfflineCredential"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/devices/{deviceId}/revoke": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Revoke my AppDevice; it receives no further credentials */
        post: operations["revokeDevice"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/health": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Application liveness (database initialized at startup) */
        get: operations["getHealth"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/nodes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List nodes of an organization (requires ACTIVE membership) */
        get: operations["listNodes"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/nodes/claim": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Claim an UNCLAIMED node for my organization (ACTIVE ADMIN + claim mode) */
        post: operations["claimNode"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/nodes/{nodeId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Node details; only members of the owning organization (foreign orgs use the public /owner endpoint) */
        get: operations["getNode"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /** ACTIVE member updates node metadata (firmware/protocol version, status note, bowl display name) */
        patch: operations["updateNode"];
        trace?: never;
    };
    "/api/v1/nodes/{nodeId}/owner": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Public owner hint for a claimed node; no observations/chip data */
        get: operations["getNodeOwner"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/observations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List raw observations with organization, node, site, chip and time filters */
        get: operations["listObservations"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/observations/ingest": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Idempotent batch ingest of raw observations (ACTIVE membership; per-item results) */
        post: operations["ingestObservations"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/observations/{observationId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Raw observation details; never readable across organizations */
        get: operations["getObservation"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Active organizations of the authenticated user's ACTIVE memberships */
        get: operations["listMyOrganizations"];
        put?: never;
        /** Create an organization; caller becomes ACTIVE ADMIN (test-phase provisioning) */
        post: operations["createOrganization"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations/directory": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Authenticated directory of discoverable, ACTIVE organizations */
        get: operations["listDiscoverableOrganizations"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Organization details; requires ACTIVE membership (tenant isolation) */
        get: operations["getOrganization"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/cats": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List cats of an organization (requires ACTIVE membership) */
        get: operations["listCats"];
        put?: never;
        /** ACTIVE member creates a cat in their own organization */
        post: operations["createCat"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/cats/{catId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Cat details; never readable across organizations */
        get: operations["getCat"];
        put?: never;
        post?: never;
        /** ADMIN deletes a cat of their own organization */
        delete: operations["deleteCat"];
        options?: never;
        head?: never;
        /** ACTIVE member updates a cat of their own organization */
        patch: operations["updateCat"];
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/chip-activity": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** ACTIVE member lists observed chips, reliable last sightings, clock issues and frozen historical feeding sites; server receipt time is separate */
        get: operations["listChipActivity"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/deployments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List deployments of an organization, optionally filtered by node */
        get: operations["listDeployments"];
        put?: never;
        /** ACTIVE ADMIN assigns a node to a feeding site for a validity range */
        post: operations["createDeployment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/deployments/move": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** ACTIVE ADMIN atomically closes the open assignment and creates a new one; frozen observation and visit attribution is unchanged */
        post: operations["moveDeployment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/deployments/{deploymentId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Deployment details; never readable across organizations */
        get: operations["getDeployment"];
        put?: never;
        post?: never;
        /** ACTIVE ADMIN deletes a deployment (stored observation attributions are kept) */
        delete: operations["deleteDeployment"];
        options?: never;
        head?: never;
        /** ACTIVE ADMIN closes or reopens a deployment by setting validUntil */
        patch: operations["closeDeployment"];
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/feeding-sites": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List feeding sites of an organization (requires ACTIVE membership) */
        get: operations["listFeedingSites"];
        put?: never;
        /** ACTIVE member creates a feeding site in their own organization */
        post: operations["createFeedingSite"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/feeding-sites/{siteId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Feeding site details; never readable across organizations */
        get: operations["getFeedingSite"];
        put?: never;
        post?: never;
        /** ADMIN deletes a feeding site (blocked while deployments reference it) */
        delete: operations["deleteFeedingSite"];
        options?: never;
        head?: never;
        /** ACTIVE member updates a feeding site of their own organization */
        patch: operations["updateFeedingSite"];
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/feeding-sites/{siteId}/cat-activity": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Compact feeding-site cat/chip activity (ACTIVE membership)
         * @description Latest reliable sighting is max end_at of persisted visit-gap-v1 visits; null until a reliable visit exists. Raw activity contributes chip identity and separate server receipt time only. Uses frozen site attribution, never current deployments. UNKNOWN/unattributed observations are not assigned to a site. Ordered by reliable sighting descending (nulls last), then chipId ascending. limit 1..1000 (default 100), offset >= 0. Foreign/missing sites return 404.
         */
        get: operations["listFeedingSiteCatActivity"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/members": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** ADMIN lists members of their own organization */
        get: operations["listMembers"];
        put?: never;
        /** ADMIN creates a user and/or ACTIVE membership (test-phase bootstrap, no mail) */
        post: operations["createMember"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/members/{membershipId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /** ADMIN changes role, activates/disables a membership, or maintains the global display name (blank clears; global across all organizations) */
        patch: operations["updateMember"];
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/nodes/{nodeId}/observations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List one node's observations within an explicit organization context */
        get: operations["listNodeObservations"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/share-policies": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** ADMIN lists the outgoing sharing policies of the active organization */
        get: operations["listSharePolicies"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/share-policies/{scope}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** ADMIN creates or replaces one outgoing policy; scope in path, audience in body */
        put: operations["upsertSharePolicy"];
        post?: never;
        /** ADMIN revokes one outgoing policy; effective on the next resolution */
        delete: operations["revokeSharePolicy"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/visibility": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /** Owner ADMIN changes discoverability; hiding invalidates outgoing grants immediately */
        patch: operations["updateOrganizationVisibility"];
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/visits": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List derived visits of an organization (requires ACTIVE membership) */
        get: operations["listVisits"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/visits/latest-per-site": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Latest reliable visit per feeding site for one chip; frozen historical site attribution, never current deployments */
        get: operations["latestVisitsPerFeedingSite"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/visits/recompute": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** ADMIN rebuilds visits from stored raw observations (no raw re-ingest, raw rows unchanged) */
        post: operations["recomputeVisits"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/organizations/{organizationId}/visits/{visitId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Visit details; never readable across organizations */
        get: operations["getVisit"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/version": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Build and HTTP API version */
        get: operations["getVersion"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        CatView: {
            chipId?: string;
            createdAt?: string;
            /** Format: uuid */
            id?: string;
            name?: string;
            notes?: string;
            organizationId?: string;
            status?: string;
            updatedAt?: string;
        };
        ChipActivityView: {
            chipId?: string;
            feedingSiteIds?: string[];
            lastReceivedAt?: string;
            lastSeenAtMillis?: string | null;
            /** Format: int64 */
            observationCount?: number;
            /** Format: int64 */
            uncertainClockCount?: number;
        };
        ClaimNodeRequest: {
            claimSignature: string;
            firmwareVersion?: string;
            /** Format: uuid */
            nodeId: string;
            /** Format: uuid */
            organizationId: string;
            publicKeyX: string;
            publicKeyY: string;
            /** Format: int64 */
            timestampMillis: number;
        };
        ClaimReceiptView: {
            /** Format: uuid */
            nodeId?: string;
            organizationId?: string;
            receipt?: string;
            tokenType?: string;
        };
        CloseDeploymentRequest: {
            /** Format: date-time */
            validUntil?: string;
        };
        CreateCatRequest: {
            chipId: string;
            name?: string;
            notes?: string;
            status?: string;
        };
        CreateDeploymentRequest: {
            /** Format: uuid */
            feedingSiteId: string;
            /** Format: uuid */
            nodeId: string;
            /** Format: date-time */
            validFrom: string;
            /** Format: date-time */
            validUntil?: string;
        };
        CreateFeedingSiteRequest: {
            description?: string;
            locationLabel?: string;
            /** Format: double */
            locationLat?: number;
            /** Format: double */
            locationLng?: number;
            name: string;
        };
        CreateMemberRequest: {
            /** @description Optional human-readable display name (global AppUser.displayName, trimmed; blank means no name; shown in every organization of this user) */
            displayName?: string | null;
            /** Format: email */
            email: string;
            /** @description Initial password: at least 12 characters, at most 72 UTF-8 bytes */
            password?: string;
            /** @enum {string} */
            role: "ADMIN" | "MEMBER";
        };
        CreateOrganizationRequest: {
            displayName: string;
            publicContact?: string;
            slug: string;
        };
        CredentialView: {
            credential?: string;
            deviceId?: string;
            /** Format: int64 */
            expiresInSeconds?: number;
            organizationId?: string;
            tokenType?: string;
        };
        CsrfView: {
            headerName?: string;
            parameterName?: string;
            token?: string;
        };
        DeploymentView: {
            createdAt?: string;
            feedingSiteId?: string;
            /** Format: uuid */
            id?: string;
            nodeId?: string;
            organizationId?: string;
            validFrom?: string;
            validUntil?: string;
        };
        DeviceView: {
            createdAt?: string;
            fingerprint?: string;
            /** Format: uuid */
            id?: string;
            label?: string;
            lastSeenAt?: string;
            revokedAt?: string;
        };
        /** @description One chip's activity at its frozen feeding site */
        FeedingSiteCatActivityView: {
            /**
             * Format: uuid
             * @description Current organization-scoped Cat ID; null for unknown chips
             */
            catId?: string | null;
            /** @description Current Cat name; may be null even for a registered Cat */
            catName?: string | null;
            chipId?: string;
            /**
             * Format: date-time
             * @description Latest server receipt of a raw observation with this frozen site (UTC); not a sighting timestamp
             */
            lastReceivedAt?: string | null;
            /**
             * Format: date-time
             * @description Latest persisted visit-gap-v1 end time (UTC); null when no reliable visit exists. Never server receipt time.
             */
            lastReliableSightingAt?: string | null;
            /**
             * Format: int64
             * @description Number of persisted visit-gap-v1 visits at this site
             */
            visitCount?: number;
        };
        FeedingSiteView: {
            createdAt?: string;
            description?: string;
            /** Format: uuid */
            id?: string;
            locationLabel?: string;
            /** Format: double */
            locationLat?: number;
            /** Format: double */
            locationLng?: number;
            name?: string;
            organizationId?: string;
            updatedAt?: string;
        };
        HealthResponse: {
            status: string;
        };
        IngestBatchRequest: {
            observations?: components["schemas"]["IngestObservationRequest"][];
            /** Format: uuid */
            organizationId: string;
        };
        IngestBatchResponse: {
            /** Format: int32 */
            conflicts?: number;
            /** Format: int32 */
            duplicates?: number;
            /** Format: int32 */
            inserted?: number;
            /** Format: int32 */
            rejected?: number;
            results?: components["schemas"]["IngestItemResult"][];
        };
        IngestItemResult: {
            message?: string;
            /** Format: uuid */
            nodeId?: string;
            sequence?: string;
            status?: string;
        };
        IngestObservationRequest: {
            /** Format: int32 */
            bootCounter?: number;
            chipId?: string;
            clockStatus?: string;
            incarnation?: string;
            monotonicMs?: string;
            /** Format: uuid */
            nodeId: string;
            observedAtMillis?: string;
            /** @example 9007199254740993 */
            sequence: string;
        };
        IssueCredentialRequest: {
            organizationId: string;
        };
        IssuerView: {
            algorithm?: string;
            /** Format: int64 */
            credentialTtlSeconds?: number;
            issuer?: string;
            jwk?: {
                [key: string]: string;
            };
            keyId?: string;
        };
        LatestSiteVisitView: {
            feedingSiteId?: string;
            feedingSiteName?: string;
            latestVisitStartAtMillis?: string;
        };
        LoginRequest: {
            /** Format: email */
            email: string;
            password: string;
        };
        MemberView: {
            displayName?: string | null;
            email?: string;
            /** Format: uuid */
            membershipId?: string;
            role?: string;
            status?: string;
            /** Format: uuid */
            userId?: string;
        };
        MembershipView: {
            /** Format: uuid */
            membershipId?: string;
            /** Format: uuid */
            organizationId?: string;
            organizationName?: string;
            organizationSlug?: string;
            role?: string;
            status?: string;
        };
        MoveDeploymentRequest: {
            /** Format: uuid */
            feedingSiteId: string;
            /** Format: uuid */
            nodeId: string;
            /** Format: date-time */
            validFrom: string;
        };
        NodeOwnerView: {
            /** Format: uuid */
            nodeId?: string;
            organizationId?: string;
            organizationName?: string;
            organizationSlug?: string;
            publicContact?: string;
            state?: string;
        };
        NodeView: {
            claimedAt?: string;
            /** @description Optional human-readable bowl label (Node.displayName, trimmed; blank means no name; never unique, never part of BLE/crypto identity) */
            displayName?: string | null;
            fingerprint?: string;
            firmwareVersion?: string;
            lastContactAt?: string;
            /** Format: uuid */
            nodeId?: string;
            organizationId?: string;
            protocolVersion?: string;
            publicKeyX?: string;
            publicKeyY?: string;
            state?: string;
            statusNote?: string;
        };
        OrganizationDirectoryEntry: {
            displayName?: string;
            /** Format: uuid */
            id?: string;
            slug?: string;
        };
        OrganizationView: {
            discoverable?: boolean;
            displayName?: string;
            /** Format: uuid */
            id?: string;
            publicContact?: string;
            slug?: string;
            status?: string;
        };
        PolicyView: {
            /** @enum {string} */
            audience?: "PRIVATE" | "ALL_DISCOVERABLE" | "ALLOWLIST";
            recipients?: components["schemas"]["RecipientView"][];
            /** Format: int32 */
            revision?: number;
            /** @enum {string} */
            scope?: "CARE" | "VISITS" | "SITE_LABEL" | "PHOTO";
        };
        RawObservationView: {
            /** Format: int32 */
            bootCounter?: number;
            chipId?: string;
            clockStatus?: string;
            deploymentId?: string;
            feedingSiteId?: string;
            /** Format: uuid */
            id?: string;
            incarnation?: string;
            monotonicMs?: string;
            nodeId?: string;
            observedAtMillis?: string;
            organizationId?: string;
            receivedAt?: string;
            sequence?: string;
        };
        RecipientView: {
            displayName?: string;
            /** Format: uuid */
            id?: string;
            slug?: string;
        };
        RecomputeVisitsRequest: {
            algorithmVersion?: string;
            /** Format: int32 */
            gapSeconds?: number;
        };
        RecomputeVisitsResponse: {
            algorithmVersion?: string;
            /** Format: int64 */
            excludedImplausibleTime?: number;
            /** Format: int64 */
            excludedUnattributed?: number;
            /** Format: int64 */
            excludedUnknownClock?: number;
            /** Format: int32 */
            gapSeconds?: number;
            /** Format: int64 */
            totalObservations?: number;
            /** Format: int64 */
            usableObservations?: number;
            /** Format: int32 */
            visitCount?: number;
            visits?: components["schemas"]["VisitView"][];
        };
        RegisterDeviceRequest: {
            label?: string;
            publicKeyX: string;
            publicKeyY: string;
        };
        RevokeSharePolicyResponse: {
            revoked?: boolean;
            scope?: string;
        };
        SessionView: {
            displayName?: string | null;
            email?: string;
            memberships?: components["schemas"]["MembershipView"][];
            /** Format: uuid */
            userId?: string;
        };
        UpdateCatRequest: {
            chipId?: string;
            name?: string;
            notes?: string;
            status?: string;
        };
        UpdateFeedingSiteRequest: {
            clearLocation?: boolean;
            description?: string;
            locationLabel?: string;
            /** Format: double */
            locationLat?: number;
            /** Format: double */
            locationLng?: number;
            name?: string;
        };
        UpdateMemberRequest: {
            /** @description Optional display name update for the underlying global AppUser. Absent/null leaves the name unchanged; blank clears it to null; a value is trimmed and stored globally (visible in all organizations). */
            displayName?: string | null;
            /** @enum {string} */
            role?: "ADMIN" | "MEMBER";
            /** @enum {string} */
            status?: "PENDING" | "ACTIVE" | "DISABLED";
        };
        UpdateNodeRequest: {
            /** @description Optional bowl label update. Absent/null leaves the name unchanged; blank clears it to null; a value is trimmed (max 100 characters, Unicode allowed) and never unique. */
            displayName?: string | null;
            firmwareVersion?: string;
            protocolVersion?: string;
            statusNote?: string;
        };
        UpdateVisibilityRequest: {
            discoverable: boolean;
        };
        UpsertSharePolicyRequest: {
            /** @enum {string} */
            audience: "PRIVATE" | "ALL_DISCOVERABLE" | "ALLOWLIST";
            /** @description Required for ALLOWLIST, forbidden otherwise: recipient organization ids */
            recipientIds?: string | null;
        };
        VersionResponse: {
            apiVersion: string;
            version: string;
        };
        VisitView: {
            algorithmVersion?: string;
            catId?: string;
            chipId?: string;
            createdAt?: string;
            endAt?: string;
            endAtMillis?: string;
            feedingSiteId?: string;
            firstObservationId?: string;
            /** Format: int32 */
            gapSeconds?: number;
            /** Format: uuid */
            id?: string;
            lastObservationId?: string;
            /** Format: int32 */
            observationCount?: number;
            organizationId?: string;
            startAt?: string;
            startAtMillis?: string;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    getCsrf: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CsrfView"];
                };
            };
        };
    };
    login: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LoginRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SessionView"];
                };
            };
        };
    };
    logout: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    getSession: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SessionView"];
                };
            };
        };
    };
    getCredentialIssuer: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["IssuerView"];
                };
            };
        };
    };
    listDevices: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DeviceView"][];
                };
            };
        };
    };
    registerDevice: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RegisterDeviceRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DeviceView"];
                };
            };
        };
    };
    issueOfflineCredential: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                deviceId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["IssueCredentialRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CredentialView"];
                };
            };
        };
    };
    revokeDevice: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                deviceId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DeviceView"];
                };
            };
        };
    };
    getHealth: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["HealthResponse"];
                };
            };
        };
    };
    listNodes: {
        parameters: {
            query: {
                organizationId: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NodeView"][];
                };
            };
        };
    };
    claimNode: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ClaimNodeRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ClaimReceiptView"];
                };
            };
        };
    };
    getNode: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                nodeId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NodeView"];
                };
            };
        };
    };
    updateNode: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                nodeId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateNodeRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NodeView"];
                };
            };
        };
    };
    getNodeOwner: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                nodeId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NodeOwnerView"];
                };
            };
        };
    };
    listObservations: {
        parameters: {
            query: {
                organizationId: string;
                nodeId?: string;
                feedingSiteId?: string;
                chipId?: string;
                fromMillis?: number;
                toMillis?: number;
                limit?: number;
                offset?: number;
                newestFirst?: boolean;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RawObservationView"][];
                };
            };
        };
    };
    ingestObservations: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["IngestBatchRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["IngestBatchResponse"];
                };
            };
        };
    };
    getObservation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                observationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RawObservationView"];
                };
            };
        };
    };
    listMyOrganizations: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrganizationView"][];
                };
            };
        };
    };
    createOrganization: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateOrganizationRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrganizationView"];
                };
            };
        };
    };
    listDiscoverableOrganizations: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrganizationDirectoryEntry"][];
                };
            };
        };
    };
    getOrganization: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrganizationView"];
                };
            };
        };
    };
    listCats: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CatView"][];
                };
            };
        };
    };
    createCat: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateCatRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CatView"];
                };
            };
        };
    };
    getCat: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
                catId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CatView"];
                };
            };
        };
    };
    deleteCat: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
                catId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    updateCat: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
                catId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateCatRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CatView"];
                };
            };
        };
    };
    listChipActivity: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ChipActivityView"][];
                };
            };
        };
    };
    listDeployments: {
        parameters: {
            query?: {
                nodeId?: string;
            };
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DeploymentView"][];
                };
            };
        };
    };
    createDeployment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateDeploymentRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DeploymentView"];
                };
            };
        };
    };
    moveDeployment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["MoveDeploymentRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DeploymentView"];
                };
            };
        };
    };
    getDeployment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
                deploymentId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DeploymentView"];
                };
            };
        };
    };
    deleteDeployment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
                deploymentId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    closeDeployment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
                deploymentId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CloseDeploymentRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DeploymentView"];
                };
            };
        };
    };
    listFeedingSites: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FeedingSiteView"][];
                };
            };
        };
    };
    createFeedingSite: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateFeedingSiteRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FeedingSiteView"];
                };
            };
        };
    };
    getFeedingSite: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
                siteId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FeedingSiteView"];
                };
            };
        };
    };
    deleteFeedingSite: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
                siteId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    updateFeedingSite: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
                siteId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateFeedingSiteRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FeedingSiteView"];
                };
            };
        };
    };
    listFeedingSiteCatActivity: {
        parameters: {
            query?: {
                limit?: number;
                offset?: number;
            };
            header?: never;
            path: {
                organizationId: string;
                siteId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FeedingSiteCatActivityView"][];
                };
            };
        };
    };
    listMembers: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MemberView"][];
                };
            };
        };
    };
    createMember: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateMemberRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MemberView"];
                };
            };
        };
    };
    updateMember: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
                membershipId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateMemberRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MemberView"];
                };
            };
        };
    };
    listNodeObservations: {
        parameters: {
            query?: {
                limit?: number;
                offset?: number;
            };
            header?: never;
            path: {
                organizationId: string;
                nodeId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RawObservationView"][];
                };
            };
        };
    };
    listSharePolicies: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PolicyView"][];
                };
            };
        };
    };
    upsertSharePolicy: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
                scope: "CARE" | "VISITS" | "SITE_LABEL" | "PHOTO";
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpsertSharePolicyRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PolicyView"];
                };
            };
        };
    };
    revokeSharePolicy: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
                scope: "CARE" | "VISITS" | "SITE_LABEL" | "PHOTO";
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RevokeSharePolicyResponse"];
                };
            };
        };
    };
    updateOrganizationVisibility: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateVisibilityRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrganizationView"];
                };
            };
        };
    };
    listVisits: {
        parameters: {
            query?: {
                feedingSiteId?: string;
                chipId?: string;
                fromMillis?: number;
                toMillis?: number;
                limit?: number;
                offset?: number;
                newestFirst?: boolean;
            };
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["VisitView"][];
                };
            };
        };
    };
    latestVisitsPerFeedingSite: {
        parameters: {
            query: {
                chipId: string;
            };
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["LatestSiteVisitView"][];
                };
            };
        };
    };
    recomputeVisits: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["RecomputeVisitsRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RecomputeVisitsResponse"];
                };
            };
        };
    };
    getVisit: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                organizationId: string;
                visitId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["VisitView"];
                };
            };
        };
    };
    getVersion: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["VersionResponse"];
                };
            };
        };
    };
}
