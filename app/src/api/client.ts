import createClient from 'openapi-fetch';
import type { paths } from './generated';

// Relative paths preserve same-origin session cookies and the local /api proxy.
export const api = createClient<paths>({ baseUrl: '', credentials: 'same-origin' });
