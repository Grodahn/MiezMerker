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
        /** ACTIVE member updates node metadata (firmware/protocol version, status note) */
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
        /** ACTIVE member assigns a node to a feeding site for a validity range */
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
        /** ACTIVE member atomically closes the open assignment and creates a new one; frozen observation and visit attribution is unchanged */
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
        /** ADMIN deletes a deployment (stored observation attributions are kept) */
        delete: operations["deleteDeployment"];
        options?: never;
        head?: never;
        /** ACTIVE member closes or reopens a deployment by setting validUntil */
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
        /** ADMIN changes role or activates/disables a membership */
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
        LoginRequest: {
            /** Format: email */
            email: string;
            password: string;
        };
        MemberView: {
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
        OrganizationView: {
            displayName?: string;
            /** Format: uuid */
            id?: string;
            publicContact?: string;
            slug?: string;
            status?: string;
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
        SessionView: {
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
            /** @enum {string} */
            role?: "ADMIN" | "MEMBER";
            /** @enum {string} */
            status?: "PENDING" | "ACTIVE" | "DISABLED";
        };
        UpdateNodeRequest: {
            firmwareVersion?: string;
            protocolVersion?: string;
            statusNote?: string;
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
    listVisits: {
        parameters: {
            query?: {
                feedingSiteId?: string;
                chipId?: string;
                fromMillis?: number;
                toMillis?: number;
                limit?: number;
                offset?: number;
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
