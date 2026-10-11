import type { components } from '../../../../../app/src/api/generated';
type Schemas = components['schemas'];
export interface SharedCareApi {
  observations(org: string, chip: string, signal: AbortSignal): Promise<Schemas['RawObservationView'][]>;
  resolve(org: string, body: Schemas['ResolveSharedCareRequest'], signal: AbortSignal): Promise<Schemas['SharedCareProfilePage']>;
  visits(org: string, body: Schemas['SharedCareVisitsRequest'], signal: AbortSignal): Promise<Schemas['SharedCareVisitPage']>;
}
export function mountSharedCare(root: HTMLElement, options: {
  organizationId: string; chipId: string; api: SharedCareApi;
  controllerFactory?: () => AbortController; pageSize?: number; onReturn?: () => void;
}): () => void;
