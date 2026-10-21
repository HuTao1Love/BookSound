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
  // A series with any book matching the progress filter shows up whole, e.g. "In progress"
  // while listening to book 2 still lists books 1 and 3.
  const matchingSeries = new Set(entries.filter((e) => matchesFilter(e, filter)).flatMap((e) => (e.book.metadata.series ? [seriesKey(e.book.metadata.series)] : [])));
  return entries
    .filter((e) => (e.book.metadata.series && matchingSeries.has(seriesKey(e.book.metadata.series))) || matchesFilter(e, filter))
    .filter((e) => {
      if (tokens.length === 0) return true;
      const m = e.book.metadata;
      const haystack = normalize([m.title, m.author, m.narrator, m.series].filter(Boolean).join(" "));
      return tokens.every((t) => haystack.includes(t));
    })
    .sort((a, b) => (descending ? -cmp(a, b) : cmp(a, b)));
}

/** Series names match ignoring case and accents, e.g. "Saga" and "saga". */
const seriesKey = (series: string) => normalize(series.trim());

/** Books of one series, or with series = null all books outside any series. */
export interface SeriesSection {
  series: string | null;
  books: LibraryEntryDto[];
}

/**
 * Groups filtered and sorted entries as the phone does: books without a series first as one
 * section, then each series in the order its first book appears, its books ordered by number.
 */
export function groupSeries(entries: LibraryEntryDto[]): SeriesSection[] {
  const standalone = entries.filter((e) => !e.book.metadata.series);
  const bySeries = new Map<string, LibraryEntryDto[]>();
  for (const e of entries) {
    const s = e.book.metadata.series;
    if (s) bySeries.set(seriesKey(s), [...(bySeries.get(seriesKey(s)) ?? []), e]);
  }
  const sections: SeriesSection[] = standalone.length > 0 ? [{ series: null, books: standalone }] : [];
  for (const books of bySeries.values()) {
    sections.push({ series: seriesName(books), books: [...books].sort((a, b) => compareInSeries(a.book, b.book)) });
  }
  return sections;
}

/** The most common spelling of the series' name (first seen wins ties). */
function seriesName(books: LibraryEntryDto[]): string {
  const counts = new Map<string, number>();
  for (const e of books) counts.set(e.book.metadata.series!, (counts.get(e.book.metadata.series!) ?? 0) + 1);
  let best = books[0].book.metadata.series!;
  for (const [name, count] of counts) if (count > counts.get(best)!) best = name;
  return best;
}

export function seriesBooks(entries: LibraryEntryDto[], series: string) {
  const key = seriesKey(series);
  return entries.filter((e) => e.book.metadata.series && seriesKey(e.book.metadata.series) === key).sort((a, b) => compareInSeries(a.book, b.book));
}

/** Authors, total length, finished books and overall progress of a series' books. */
export function seriesStats(books: LibraryEntryDto[]) {
  const durationMs = books.reduce((sum, e) => sum + e.book.durationMs, 0);
  const listenedMs = books.reduce((sum, e) => sum + (e.playback?.finished ? e.book.durationMs : Math.min(e.playback?.positionMs ?? 0, e.book.durationMs)), 0);
  return {
    authors: [...new Set(books.flatMap((e) => (e.book.metadata.author ? [e.book.metadata.author] : [])))],
    durationMs,
    finished: books.filter((e) => e.playback?.finished).length,
    progress: durationMs > 0 ? Math.min(1, listenedMs / durationMs) : 0,
  };
}

/** The book to continue a series with: the one listened to last, else the first unfinished one. */
export function upNext(books: LibraryEntryDto[]): LibraryEntryDto | undefined {
  const available = books.filter((e) => !e.playback?.finished);
  const started = available.filter((e) => (e.playback?.positionMs ?? 0) > 0);
  return started.sort((a, b) => (b.playback?.lastPlayedAt ?? 0) - (a.playback?.lastPlayedAt ?? 0))[0] ?? available[0];
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
