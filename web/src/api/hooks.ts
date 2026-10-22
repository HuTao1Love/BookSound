import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { api, paths } from "./client";
import type {
  DeviceDto,
  EventDto,
  ImportJobDto,
  LibraryEntryDto,
  PreferencesDto,
  ServerSettingsDto,
  UploadDto,
  SessionDto,
  StatusDto,
  TorrentDto,
  UserDto,
  WatchProfileDto,
} from "./types.gen";

export const keys = {
  status: ["status"],
  me: ["me"],
  books: ["books"],
  book: (id: string) => ["books", id],
  imports: ["imports"],
  uploads: ["uploads"],
  torrents: ["torrents"],
  devices: ["devices"],
  users: ["users"],
  settings: ["settings"],
  watchProfiles: ["watchProfiles"],
  preferences: ["preferences"],
};

export const useStatus = () => useQuery({ queryKey: keys.status, queryFn: () => api.get<StatusDto>(paths.status) });
export const useMe = () => useQuery({ queryKey: keys.me, queryFn: () => api.get<SessionDto>(paths.me), retry: false });
// Live updates come over server-sent events, but proxies (the router's KeenDNS among them) may cut that stream:
// lists that show progress also poll, quickly while something is under way.
/** Stages of an import job that is still under way. */
export const ACTIVE_STAGES = ["QUEUED", "PREPARING", "CONVERTING", "WRITING", "VERIFYING", "FINISHING"];

export const useBooks = () => useQuery({ queryKey: keys.books, queryFn: () => api.get<LibraryEntryDto[]>(paths.books), refetchInterval: 60_000 });
export const useBook = (id: string) => useQuery({ queryKey: keys.book(id), queryFn: () => api.get<LibraryEntryDto>(paths.book(id)) });
export const useImports = () =>
  useQuery({
    queryKey: keys.imports,
    queryFn: () => api.get<ImportJobDto[]>(paths.imports),
    refetchInterval: (q) => (q.state.data?.some((j) => ACTIVE_STAGES.includes(j.stage)) ? 3_000 : 30_000),
  });
export const useTorrents = () =>
  useQuery({
    queryKey: keys.torrents,
    queryFn: () => api.get<TorrentDto[]>(paths.torrents),
    refetchInterval: (q) => (q.state.data?.some((t) => t.phase !== "COMPLETED" && t.phase !== "FAILED") ? 3_000 : 30_000),
  });
/** Files on their way to the server from any device. */
export const useUploads = () =>
  useQuery({
    queryKey: keys.uploads,
    queryFn: () => api.get<UploadDto[]>(paths.uploads),
    refetchInterval: (q) => (q.state.data?.length ? 3_000 : 15_000),
  });
export const useDevices = () => useQuery({ queryKey: keys.devices, queryFn: () => api.get<DeviceDto[]>(paths.devices) });
export const useUsers = () => useQuery({ queryKey: keys.users, queryFn: () => api.get<UserDto[]>(paths.users) });
export const useSettings = () => useQuery({ queryKey: keys.settings, queryFn: () => api.get<ServerSettingsDto>(paths.settings) });
export const useWatchProfiles = () => useQuery({ queryKey: keys.watchProfiles, queryFn: () => api.get<WatchProfileDto[]>(paths.watchProfiles) });
export const usePreferences = () => useQuery({ queryKey: keys.preferences, queryFn: () => api.get<PreferencesDto>(paths.preferences) });

/** Live updates from the server: job and torrent progress go straight into the cache, library changes refetch. */
export function useServerEvents(enabled: boolean) {
  const client = useQueryClient();
  useEffect(() => {
    if (!enabled) return;
    const source = new EventSource(paths.events);
    const parse = (e: MessageEvent) => JSON.parse(e.data) as EventDto;
    const onJob = (e: MessageEvent) => {
      const job = parse(e).job;
      if (!job) return;
      client.setQueryData<ImportJobDto[]>(keys.imports, (old) =>
        old ? [job, ...old.filter((j) => j.id !== job.id)].sort((a, b) => b.createdAt - a.createdAt) : old,
      );
      if (job.stage === "DONE") client.invalidateQueries({ queryKey: keys.books });
    };
    const onTorrent = (e: MessageEvent) => {
      const torrent = parse(e).torrent;
      if (!torrent) return;
      client.setQueryData<TorrentDto[]>(keys.torrents, (old) =>
        old ? [torrent, ...old.filter((t) => t.id !== torrent.id)].sort((a, b) => b.addedAt - a.addedAt) : old,
      );
    };
    const onTorrentRemoved = (e: MessageEvent) => {
      const torrent = parse(e).torrent;
      if (torrent) client.setQueryData<TorrentDto[]>(keys.torrents, (old) => old?.filter((t) => t.id !== torrent.id));
    };
    let pending: number | undefined;
    const onLibrary = () => {
      // Scans send one event per book; refetch once they calm down.
      window.clearTimeout(pending);
      pending = window.setTimeout(() => client.invalidateQueries({ queryKey: keys.books }), 1000);
    };
    source.addEventListener("job", onJob);
    source.addEventListener("torrent", onTorrent);
    source.addEventListener("torrent_removed", onTorrentRemoved);
    source.addEventListener("library", onLibrary);
    return () => {
      window.clearTimeout(pending);
      source.close();
    };
  }, [enabled, client]);
}
