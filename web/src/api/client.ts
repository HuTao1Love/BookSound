import type { ErrorDto } from "./types.gen";

/** An error answer of the server, with its code from ErrorCodes. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message?: string | null) {
    super(message || code);
    this.status = status;
    this.code = code;
  }
}

let onUnauthorized: () => void = () => {};

/** Called when the session ends (expired, revoked): the app goes back to the login page. */
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

/**
 * A request with the session cookie. Every request carries the CSRF header the server wants on changes:
 * a page on another site can make the browser send the cookie, but not this header.
 */
export async function request<T>(method: string, path: string, body?: unknown, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = { "X-BookSound-Csrf": "1" };
  let payload: BodyInit | undefined;
  if (body instanceof Blob || body instanceof ArrayBuffer) {
    payload = body;
  } else if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    payload = JSON.stringify(body);
  }
  const response = await fetch(path, {
    ...init,
    method,
    headers: { ...headers, ...(init?.headers as Record<string, string> | undefined) },
    body: payload,
    credentials: "same-origin",
  });
  if (response.status === 401 && !path.startsWith("/api/auth/")) onUnauthorized();
  if (!response.ok) {
    let error: ErrorDto | undefined;
    try {
      error = await response.json();
    } catch {
      // not JSON
    }
    throw new ApiError(response.status, error?.code ?? String(response.status), error?.message);
  }
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const api = {
  get: <T>(path: string) => request<T>("GET", path),
  post: <T>(path: string, body?: unknown) => request<T>("POST", path, body ?? {}),
  put: <T>(path: string, body?: unknown) => request<T>("PUT", path, body),
  patch: <T>(path: string, body?: unknown) => request<T>("PATCH", path, body),
  delete: <T>(path: string) => request<T>("DELETE", path),
};

export const paths = {
  status: "/api/status",
  setup: "/api/auth/setup",
  webLogin: "/api/auth/web-login",
  logout: "/api/auth/logout",
  me: "/api/auth/me",
  password: "/api/auth/password",
  approveCode: "/api/auth/device-code/approve",
  devices: "/api/devices",
  books: "/api/books",
  book: (id: string) => `/api/books/${id}`,
  bookFile: (id: string) => `/api/books/${id}/file`,
  bookCover: (id: string, size?: number) => `/api/books/${id}/cover${size ? `?size=${size}` : ""}`,
  watchFile: (id: string, profile: string) => `/api/books/${id}/watch-file?profile=${encodeURIComponent(profile)}`,
  syncPush: "/api/sync/push",
  preferences: "/api/preferences",
  uploads: "/api/uploads",
  upload: (id: string) => `/api/uploads/${id}`,
  imports: "/api/imports",
  torrents: "/api/torrents",
  events: "/api/events",
  users: "/api/admin/users",
  settings: "/api/admin/settings",
  rescan: "/api/admin/rescan",
  watchProfiles: "/api/watch-profiles",
};
