import type { BookDto, ChapterDto, LibraryEntryDto, PlaybackDto } from "./api/types.gen";

/* Search, sorting and series grouping, the same rules as :core's LibraryQuery. */

export type SortField = "RECENT" | "ADDED" | "TITLE" | "AUTHOR" | "SERIES" | "DURATION" | "PROGRESS";
export type ProgressFilter = "ALL" | "NOT_STARTED" | "IN_PROGRESS" | "FINISHED";

export const sortFields: SortField[] = ["RECENT", "ADDED", "TITLE", "AUTHOR", "SERIES", "DURATION", "PROGRESS"];
export const progressFilters: ProgressFilter[] = ["ALL", "NOT_STARTED", "IN_PROGRESS", "FINISHED"];

export const defaultDescending = (field: SortField) => field === "RECENT" || field === "ADDED" || field === "PROGRESS";

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: "base" });

export function normalize(text: string): string {
  return text.normalize("NFKD").replace(/\p{M}/gu, "").toLowerCase().replace(/ё/g, "е");
}

export function progressOf(entry: LibraryEntryDto): number {
  const p = entry.playback;
  if (p?.finished) return 1;
  if (!p || entry.book.durationMs <= 0) return 0;
  return Math.min(1, Math.max(0, p.positionMs / entry.book.durationMs));
}

function matchesFilter(entry: LibraryEntryDto, filter: ProgressFilter) {
  const p = entry.playback;
  switch (filter) {
    case "ALL":
      return true;
    case "NOT_STARTED":
      return !p?.finished && (p?.positionMs ?? 0) <= 0;
    case "IN_PROGRESS":
      return !p?.finished && (p?.positionMs ?? 0) > 0;
    case "FINISHED":
      return !!p?.finished;
  }
}

function sortableTitle(title: string) {
  return title.replace(/^(the|a|an)\s+/i, "");
}

function compare(field: SortField) {
  const missing = "￿";
  return (a: LibraryEntryDto, b: LibraryEntryDto): number => {
    switch (field) {
      case "RECENT":
        return (a.playback?.lastPlayedAt ?? 0) - (b.playback?.lastPlayedAt ?? 0) || a.book.addedAt - b.book.addedAt;
      case "ADDED":
        return a.book.addedAt - b.book.addedAt;
      case "TITLE":
        return collator.compare(sortableTitle(a.book.metadata.title), sortableTitle(b.book.metadata.title));
      case "AUTHOR":
        return collator.compare(a.book.metadata.author ?? missing, b.book.metadata.author ?? missing) || compareInSeries(a.book, b.book);
      case "SERIES":
        return collator.compare(a.book.metadata.series ?? missing, b.book.metadata.series ?? missing) || compareInSeries(a.book, b.book);
      case "DURATION":
        return a.book.durationMs - b.book.durationMs;
      case "PROGRESS":
        return progressOf(a) - progressOf(b);
    }
  };
}

export function seriesIndexKey(index?: string | null) {
  const n = index == null ? NaN : Number(index);
  return Number.isFinite(n) ? n : Number.MAX_VALUE;
}

function compareInSeries(a: BookDto, b: BookDto) {
  return seriesIndexKey(a.metadata.seriesIndex) - seriesIndexKey(b.metadata.seriesIndex) || collator.compare(a.metadata.title, b.metadata.title);
}

export function applyQuery(entries: LibraryEntryDto[], text: string, sort: SortField, descending: boolean, filter: ProgressFilter) {
  const tokens = normalize(text).split(/\s+/).filter(Boolean);
  const cmp = compare(sort);
  return entries
    .filter((e) => matchesFilter(e, filter))
    .filter((e) => {
      if (tokens.length === 0) return true;
      const m = e.book.metadata;
      const haystack = normalize([m.title, m.author, m.narrator, m.series].filter(Boolean).join(" "));
      return tokens.every((t) => haystack.includes(t));
    })
    .sort((a, b) => (descending ? -cmp(a, b) : cmp(a, b)));
}

export interface LibraryItem {
  kind: "book" | "series";
  entry: LibraryEntryDto;
  series?: string;
  books?: LibraryEntryDto[];
}

/** Books of one series collapse into one item, placed where the series' first book would be. */
export function groupSeries(entries: LibraryEntryDto[]): LibraryItem[] {
  const bySeries = new Map<string, LibraryEntryDto[]>();
  for (const e of entries) {
    const s = e.book.metadata.series;
    if (s) bySeries.set(s, [...(bySeries.get(s) ?? []), e]);
  }
  const placed = new Set<string>();
  const items: LibraryItem[] = [];
  for (const e of entries) {
    const s = e.book.metadata.series;
    const books = s ? bySeries.get(s)! : undefined;
    if (!s || books!.length < 2) {
      items.push({ kind: "book", entry: e });
    } else if (!placed.has(s)) {
      placed.add(s);
      items.push({ kind: "series", entry: e, series: s, books: [...books!].sort((a, b) => compareInSeries(a.book, b.book)) });
    }
  }
  return items;
}

export function seriesBooks(entries: LibraryEntryDto[], series: string) {
  return entries.filter((e) => e.book.metadata.series === series).sort((a, b) => compareInSeries(a.book, b.book));
}

export function chapterAt(chapters: ChapterDto[], positionMs: number): number {
  let index = 0;
  for (let i = 0; i < chapters.length; i++) if (chapters[i].startMs <= positionMs) index = i;
  return index;
}

export function formatDuration(ms: number, withSeconds = true): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const pad = (n: number) => n.toString().padStart(2, "0");
  if (!withSeconds) return h > 0 ? `${h} h ${m} min` : `${m} min`;
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KiB", "MiB", "GiB", "TiB"];
  let value = bytes;
  let unit = -1;
  do {
    value /= 1024;
    unit++;
  } while (value >= 1024 && unit < units.length - 1);
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}

export function isStarted(p?: PlaybackDto | null) {
  return !!p && !p.finished && p.positionMs > 0;
}
