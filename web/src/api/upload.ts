import { api, ApiError, paths, request } from "./client";
import type { UploadCreateRequest, UploadDto } from "./types.gen";

const CHUNK = 8 * 1024 * 1024;

/**
 * Sends a file in 8 MiB chunks (the router's proxy and flaky mobile connections cope with that), resuming
 * where the server's copy ends after an error.
 */
export async function uploadFile(
  file: File,
  create: Omit<UploadCreateRequest, "fileName" | "sizeBytes">,
  onProgress: (sent: number) => void,
  signal?: AbortSignal,
): Promise<UploadDto> {
  let upload = await api.post<UploadDto>(paths.uploads, { ...create, fileName: file.name, sizeBytes: file.size });
  try {
    return await sendChunks(file, upload, onProgress, signal);
  } catch (e) {
    // The next attempt starts a new upload: the server needn't keep (and show) this one.
    void api.delete(paths.upload(upload.id)).catch(() => undefined);
    throw e;
  }
}

async function sendChunks(file: File, upload: UploadDto, onProgress: (sent: number) => void, signal?: AbortSignal): Promise<UploadDto> {
  let offset = 0;
  let failures = 0;
  while (offset < file.size) {
    if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
    const end = Math.min(offset + CHUNK, file.size);
    try {
      upload = await request<UploadDto>("PUT", paths.upload(upload.id), file.slice(offset, end), {
        headers: { "Content-Range": `bytes ${offset}-${end - 1}/${file.size}`, "Content-Type": "application/octet-stream" },
        signal,
      });
      offset = upload.receivedBytes;
      failures = 0;
      onProgress(offset);
    } catch (e) {
      if (signal?.aborted || (e instanceof ApiError && e.status !== 409 && e.status < 500) || ++failures > 5) throw e;
      await new Promise((r) => setTimeout(r, 1000 * failures));
      offset = (await api.get<UploadDto>(paths.upload(upload.id))).receivedBytes;
    }
  }
  return upload;
}
