import { useQueryClient } from "@tanstack/react-query";
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, paths } from "../api/client";
import { keys } from "../api/hooks";
import type { BookDto, LibraryEntryDto, PlaybackDto, PreferencesDto, PushResponse } from "../api/types.gen";
import { chapterAt } from "../library";
import { Equalizer, type VoicePreset } from "./equalizer";

export type SleepTimer = { kind: "off" } | { kind: "minutes"; endsAt: number } | { kind: "chapter" };

/** A position saved on another device that differs from where this player is. */
export interface RemoteOffer {
  positionMs: number;
  device: string;
}

interface PlayerState {
  book: BookDto | null;
  playing: boolean;
  positionMs: number;
  durationMs: number;
  speed: number;
  chapterIndex: number;
  sleep: SleepTimer;
  preset: VoicePreset;
  remoteOffer: RemoteOffer | null;
  skipBack: number;
  skipForward: number;
}

interface PlayerApi extends PlayerState {
  play: (entry: LibraryEntryDto, fromMs?: number) => void;
  toggle: () => void;
  seek: (ms: number) => void;
  skip: (seconds: number) => void;
  setSpeed: (speed: number) => void;
  goToChapter: (index: number) => void;
  setSleep: (timer: SleepTimer) => void;
  setPreset: (preset: VoicePreset) => void;
  acceptRemote: () => void;
  dismissRemote: () => void;
  setFinished: (finished: boolean) => void;
}

const PlayerContext = createContext<PlayerApi | null>(null);

export const usePlayer = () => useContext(PlayerContext)!;

const SAVE_INTERVAL_MS = 10_000;
const SMART_REWIND_AFTER_MS = 60_000;
const SMART_REWIND_MS = 10_000;
const FADE_MS = 10_000;
const REMOTE_DIFFERENCE_MS = 30_000;

/**
 * The web app's player: one audio element for the whole app, so playback goes on while browsing. It saves the
 * position like the phone does (every 10 s while playing, and on pause and seek) and shows lock-screen controls
 * through the Media Session API.
 */
export function PlayerProvider({ deviceId, children }: { deviceId: string; children: ReactNode }) {
  const audio = useMemo(() => {
    const a = new Audio();
    a.preload = "auto";
    return a;
  }, []);
  const equalizer = useMemo(() => new Equalizer(audio), [audio]);
  const queryClient = useQueryClient();

  const [state, setState] = useState<PlayerState>({
    book: null, playing: false, positionMs: 0, durationMs: 0, speed: 1, chapterIndex: 0, sleep: { kind: "off" },
    preset: "OFF", remoteOffer: null, skipBack: 15, skipForward: 30,
  });
  const stateRef = useRef(state);
  stateRef.current = state;

  const playback = useRef<PlaybackDto | null>(null);
  const pausedAt = useRef<number | null>(null);
  const lastSave = useRef(0);
  const preferences = useRef<PreferencesDto | null>(null);
  const pendingSeek = useRef<number | null>(null);

  const update = (patch: Partial<PlayerState>) => setState((s) => ({ ...s, ...patch }));

  // ---------------------------------------------------------------------------------------- saving

  const save = useCallback(
    async (patch?: Partial<PlaybackDto>) => {
      const book = stateRef.current.book;
      // While a book loads the element is not at its position yet (and switching books fires a "pause" for the
      // old file): saving now would store the start of the book over the real position.
      if (!book || pendingSeek.current !== null) return;
      const now = Date.now();
      const previous = playback.current;
      const record: PlaybackDto = {
        bookId: book.id,
        positionMs: Math.round(audio.currentTime * 1000),
        speed: audio.playbackRate,
        finished: previous?.finished ?? false,
        lastPlayedAt: now,
        stamp: { revision: (previous?.stamp.revision ?? 0) + 1, updatedAt: now, updatedBy: deviceId, deleted: false },
        ...patch,
      };
      playback.current = record;
      lastSave.current = now;
      try {
        const result = await api.post<PushResponse>(paths.syncPush, { changes: { books: [], playback: [record] } });
        const revision = result.accepted[`playback:${book.id}`];
        if (revision !== undefined) playback.current = { ...record, stamp: { ...record.stamp, revision } };
        queryClient.setQueryData<LibraryEntryDto[]>(keys.books, (old) => old?.map((e) => (e.book.id === book.id ? { ...e, playback: playback.current } : e)));
      } catch {
        // Offline for a moment: the next save carries the position.
      }
    },
    [audio, deviceId, queryClient],
  );

  // ------------------------------------------------------------------------------------ preferences

  const loadPreferences = useCallback(async () => {
    try {
      const prefs = await api.get<PreferencesDto>(paths.preferences);
      preferences.current = prefs;
      update({ skipBack: prefs.skipBackSeconds, skipForward: prefs.skipForwardSeconds });
    } catch {
      // defaults
    }
  }, []);

  useEffect(() => {
    void loadPreferences();
  }, [loadPreferences]);

  // ------------------------------------------------------------------------------------------ media

  useEffect(() => {
    const onTime = () => {
      const s = stateRef.current;
      const positionMs = audio.currentTime * 1000;
      const chapters = s.book?.chapters ?? [];
      const chapterIndex = chapterAt(chapters, positionMs);
      if (s.sleep.kind === "chapter" && chapterIndex !== s.chapterIndex && !audio.paused) {
        audio.pause();
        update({ sleep: { kind: "off" } });
      }
      update({ positionMs, chapterIndex, durationMs: (audio.duration || 0) * 1000 || (s.book?.durationMs ?? 0) });
      if (!audio.paused && Date.now() - lastSave.current > SAVE_INTERVAL_MS) void save();
    };
    const onPlay = () => {
      update({ playing: true });
      equalizer.resume();
    };
    const onPause = () => {
      update({ playing: false });
      pausedAt.current = Date.now();
      if (!audio.ended) void save();
    };
    const onEnded = () => {
      update({ playing: false });
      void save({ finished: true, positionMs: Math.round(audio.duration * 1000) });
    };
    const onLoaded = () => {
      if (pendingSeek.current !== null) {
        audio.currentTime = pendingSeek.current / 1000;
        pendingSeek.current = null;
      }
      audio.playbackRate = stateRef.current.speed;
      update({ durationMs: audio.duration * 1000 });
    };
    audio.addEventListener("timeupdate", onTime);
    audio.addEventListener("play", onPlay);
    audio.addEventListener("pause", onPause);
    audio.addEventListener("ended", onEnded);
    audio.addEventListener("loadedmetadata", onLoaded);
    return () => {
      audio.removeEventListener("timeupdate", onTime);
      audio.removeEventListener("play", onPlay);
      audio.removeEventListener("pause", onPause);
      audio.removeEventListener("ended", onEnded);
      audio.removeEventListener("loadedmetadata", onLoaded);
    };
  }, [audio, equalizer, save]);

  // Saves the position when the tab is closed or hidden (a phone browser may be killed in the background).
  useEffect(() => {
    const onHide = () => {
      if (document.visibilityState === "hidden" && stateRef.current.book) void save();
    };
    document.addEventListener("visibilitychange", onHide);
    return () => document.removeEventListener("visibilitychange", onHide);
  }, [save]);

  // ---------------------------------------------------------------------------------------- sleep

  useEffect(() => {
    if (state.sleep.kind !== "minutes") {
      audio.volume = 1;
      return;
    }
    const endsAt = state.sleep.endsAt;
    const timer = window.setInterval(() => {
      const left = endsAt - Date.now();
      if (left <= 0) {
        audio.pause();
        audio.volume = 1;
        update({ sleep: { kind: "off" } });
      } else {
        // The last seconds fade out, so the stop doesn't wake anyone up.
        audio.volume = left < FADE_MS ? Math.max(0.05, left / FADE_MS) : 1;
      }
    }, 250);
    return () => window.clearInterval(timer);
  }, [audio, state.sleep]);

  // ----------------------------------------------------------------------------------- controls

  const presetFor = (bookId: string): VoicePreset => (preferences.current?.voicePresets?.[bookId] as VoicePreset | undefined) ?? "OFF";

  const play = useCallback(
    (entry: LibraryEntryDto, fromMs?: number) => {
      const book = entry.book;
      const current = stateRef.current.book;
      if (current?.id === book.id && fromMs === undefined) {
        void audio.play();
        return;
      }
      if (current) void save();
      playback.current = entry.playback ?? null;
      const start = fromMs ?? (entry.playback?.finished ? 0 : entry.playback?.positionMs ?? 0);
      const speed = entry.playback?.speed ?? preferences.current?.defaultSpeed ?? 1;
      const preset = presetFor(book.id);
      update({ book, positionMs: start, durationMs: book.durationMs, speed, chapterIndex: chapterAt(book.chapters ?? [], start), remoteOffer: null, preset });
      pendingSeek.current = start;
      pausedAt.current = null;
      audio.src = paths.bookFile(book.id);
      audio.playbackRate = speed;
      equalizer.apply(preset);
      void audio.play();
      // Books from the library list come without chapters: fetch them while the audio starts.
      if (!book.chapters?.length) {
        api.get<LibraryEntryDto>(paths.book(book.id)).then((full) => {
          if (stateRef.current.book?.id !== book.id) return;
          const chapters = full.book.chapters ?? [];
          setState((s) => ({ ...s, book: full.book, chapterIndex: chapterAt(chapters, audio.currentTime * 1000) }));
        }).catch(() => {});
      }
    },
    [audio, equalizer, save],
  );

  const checkRemote = useCallback(async () => {
    const book = stateRef.current.book;
    if (!book) return;
    try {
      const entry = await api.get<LibraryEntryDto>(paths.book(book.id));
      const remote = entry.playback;
      if (remote && remote.stamp.updatedBy !== deviceId && remote.stamp.updatedAt > (playback.current?.stamp.updatedAt ?? 0) &&
        Math.abs(remote.positionMs - audio.currentTime * 1000) > REMOTE_DIFFERENCE_MS) {
        update({ remoteOffer: { positionMs: remote.positionMs, device: remote.stamp.updatedBy } });
        playback.current = { ...remote };
      }
    } catch {
      // offline
    }
  }, [audio, deviceId]);

  const toggle = useCallback(() => {
    if (!stateRef.current.book) return;
    if (audio.paused) {
      // Smart rewind: after a longer pause, start a little earlier to pick the thread up again.
      if (pausedAt.current && Date.now() - pausedAt.current > SMART_REWIND_AFTER_MS) {
        audio.currentTime = Math.max(0, audio.currentTime - SMART_REWIND_MS / 1000);
        void checkRemote();
      }
      void audio.play();
    } else {
      audio.pause();
    }
  }, [audio, checkRemote]);

  const seek = useCallback(
    (ms: number) => {
      audio.currentTime = Math.max(0, Math.min(ms, (audio.duration || Infinity) * 1000) / 1000);
      update({ positionMs: audio.currentTime * 1000 });
      void save();
    },
    [audio, save],
  );

  const skip = useCallback((seconds: number) => seek(audio.currentTime * 1000 + seconds * 1000), [audio, seek]);

  const setSpeed = useCallback(
    (speed: number) => {
      audio.playbackRate = speed;
      update({ speed });
      void save({ speed });
    },
    [audio, save],
  );

  const goToChapter = useCallback(
    (index: number) => {
      const chapters = stateRef.current.book?.chapters ?? [];
      if (index < 0 || index >= chapters.length) return;
      seek(chapters[index].startMs);
    },
    [seek],
  );

  const setPreset = useCallback(
    (preset: VoicePreset) => {
      const book = stateRef.current.book;
      equalizer.apply(preset);
      update({ preset });
      if (!book) return;
      const prefs = preferences.current;
      if (!prefs) return;
      const voicePresets = { ...prefs.voicePresets };
      if (preset === "OFF") delete voicePresets[book.id];
      else voicePresets[book.id] = preset;
      const next = { ...prefs, voicePresets };
      preferences.current = next;
      void api.put<PreferencesDto>(paths.preferences, next).then((saved) => (preferences.current = saved)).catch(() => {});
    },
    [equalizer],
  );

  const setFinished = useCallback(
    (finished: boolean) => {
      void save({ finished, positionMs: finished ? stateRef.current.book?.durationMs ?? 0 : 0 });
      if (finished) audio.pause();
    },
    [audio, save],
  );

  // --------------------------------------------------------------------------- lock-screen controls

  useEffect(() => {
    if (!("mediaSession" in navigator)) return;
    const session = navigator.mediaSession;
    const book = state.book;
    if (!book) return;
    session.metadata = new MediaMetadata({
      title: book.chapters?.[state.chapterIndex]?.title ?? book.metadata.title,
      artist: book.metadata.author ?? "",
      album: book.metadata.title,
      artwork: book.coverSha256 ? [{ src: paths.bookCover(book.id, 512), sizes: "512x512", type: "image/jpeg" }] : [],
    });
    const handlers: [MediaSessionAction, MediaSessionActionHandler][] = [
      ["play", () => toggle()],
      ["pause", () => toggle()],
      ["seekbackward", () => skip(-stateRef.current.skipBack)],
      ["seekforward", () => skip(stateRef.current.skipForward)],
      ["previoustrack", () => goToChapter(stateRef.current.chapterIndex - 1)],
      ["nexttrack", () => goToChapter(stateRef.current.chapterIndex + 1)],
      ["seekto", (d) => d.seekTime !== undefined && seek(d.seekTime * 1000)],
    ];
    for (const [action, handler] of handlers) {
      try {
        session.setActionHandler(action, handler);
      } catch {
        // not supported here
      }
    }
  }, [state.book, state.chapterIndex, toggle, skip, goToChapter, seek]);

  useEffect(() => {
    if (!("mediaSession" in navigator) || !state.book || !state.durationMs) return;
    navigator.mediaSession.playbackState = state.playing ? "playing" : "paused";
    try {
      navigator.mediaSession.setPositionState({
        duration: state.durationMs / 1000,
        position: Math.min(state.positionMs, state.durationMs) / 1000,
        playbackRate: state.speed,
      });
    } catch {
      // ignore inconsistent states while loading
    }
  }, [state.book, state.playing, state.durationMs, state.positionMs, state.speed]);

  const value: PlayerApi = {
    ...state,
    play,
    toggle,
    seek,
    skip,
    setSpeed,
    goToChapter,
    setSleep: (sleep) => update({ sleep }),
    setPreset,
    acceptRemote: () => {
      const offer = stateRef.current.remoteOffer;
      if (offer) seek(offer.positionMs);
      update({ remoteOffer: null });
    },
    dismissRemote: () => update({ remoteOffer: null }),
    setFinished,
  };

  return <PlayerContext.Provider value={value}>{children}</PlayerContext.Provider>;
}
