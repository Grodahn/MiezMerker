import type { components } from '../../../../../app/src/api/generated';
type Schemas = components['schemas'];
export interface SharedCareApi {
  observations(org: string, chip: string, signal: AbortSignal): Promise<Schemas['RawObservationView'][]>;
  resolve(org: string, body: Schemas['ResolveSharedCareRequest'], signal: AbortSignal): Promise<Schemas['SharedCareProfilePage']>;
  visits(org: string, body: Schemas['SharedCareVisitsRequest'], signal: AbortSignal): Promise<Schemas['SharedCareVisitPage']>;
  /**
   * Foreign cat photo for one source organization. Resolves a transient
   * blob URL when CARE plus PHOTO effectively permit it, otherwise null.
   * Implementations must use no-store requests, keep bytes out of every
   * browser storage, and let the presenter revoke the URL.
   */
  photo(org: string, body: Schemas['SharedCarePhotoRequest'], signal: AbortSignal): Promise<string | null>;
}
export function mountSharedCare(root: HTMLElement, options: {
  organizationId: string; chipId: string; api: SharedCareApi;
  controllerFactory?: () => AbortController; pageSize?: number; onReturn?: () => void;
}): () => void;
