import ChevronLeftRoundedIcon from "@mui/icons-material/ChevronLeftRounded";
import ChevronRightRoundedIcon from "@mui/icons-material/ChevronRightRounded";
import KeyboardArrowRightRoundedIcon from "@mui/icons-material/KeyboardArrowRightRounded";
import { alpha, Box, ButtonBase, IconButton, Typography, useTheme } from "@mui/material";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import type { LibraryEntryDto } from "../api/types.gen";
import { formatDuration, isStarted, progressOf, seriesStats } from "../library";
import { usePlayer } from "../player/PlayerContext";
import BookCover, { CoverBackdrop } from "./BookCover";
import { PlayButton, ProgressLine } from "./Ui";

/** Cover width in the shelves, as on the phone: smaller on narrow screens. */
const SHELF_COVER = { xs: 128, md: 152 };

/** "Finished", "3 h left" or the book's length, under a cover. */
export function useRemaining() {
  const { t } = useTranslation();
  return (e: LibraryEntryDto) => e.playback?.finished
    ? t("book.finished")
    : isStarted(e.playback)
      ? t("book.left", { time: formatDuration(Math.max(0, e.book.durationMs - (e.playback?.positionMs ?? 0)), false) })
      : formatDuration(e.book.durationMs, false);
}

/** "Author · 3 books · 12 h", the line under a series' name. */
export function useSeriesLine() {
  const { t } = useTranslation();
  return (books: LibraryEntryDto[]) => {
    const stats = seriesStats(books);
    return [stats.authors.slice(0, 2).join(", "), t("library.booksInSeries", { count: books.length }), formatDuration(stats.durationMs, false)]
      .filter(Boolean).join(" · ");
  };
}

/** Up to three covers fanned out, as a small series emblem. */
export function StackedCovers({ books, size = 52 }: { books: LibraryEntryDto[]; size?: number }) {
  const shown = books.slice(0, 3);
  const step = size * 0.23;
  return (
    <Box sx={{ position: "relative", flexShrink: 0, width: size + step * Math.max(0, shown.length - 1), height: size }}>
      {shown.map((e, i) => (
        <Box key={e.book.id} sx={{ position: "absolute", top: 0, left: step * i, width: size, zIndex: shown.length - i, filter: "drop-shadow(0 4px 8px rgba(0,0,0,0.3))" }}>
          <BookCover book={e.book} size={size > 64 ? 256 : 128} radius={(size * 0.23) / 14} />
        </Box>
      ))}
    </Box>
  );
}

/** The "N/M done" pill of a series with finished books. */
export function FinishedPill({ books }: { books: LibraryEntryDto[] }) {
  const { t } = useTranslation();
  const finished = seriesStats(books).finished;
  if (finished === 0) return null;
  return (
    <Box sx={{ flexShrink: 0, px: 1.25, py: 0.5, borderRadius: 999, bgcolor: (t) => alpha(t.palette.secondary.main, 0.18), color: "secondary.main", fontSize: 12, fontWeight: 700, lineHeight: 1.3, whiteSpace: "nowrap" }}>
      {t("library.finishedOf", { finished, total: books.length })}
    </Box>
  );
}

/** A horizontal row of covers ([inset] in spacing units); on wide screens arrows scroll it a page at a time. */
export function Shelf({ books, numbered, onOpen, inset = 0 }: { books: LibraryEntryDto[]; numbered: boolean; onOpen: (e: LibraryEntryDto) => void; inset?: number | Record<string, number> }) {
  const row = useRef<HTMLDivElement>(null);
  const [edges, setEdges] = useState({ start: true, end: true });
  const update = () => {
    const el = row.current;
    if (el) setEdges({ start: el.scrollLeft <= 4, end: el.scrollLeft + el.clientWidth >= el.scrollWidth - 4 });
  };
  useEffect(() => {
    update();
    const el = row.current;
    if (!el) return;
    const observer = new ResizeObserver(update);
    observer.observe(el);
    return () => observer.disconnect();
  }, [books.length]);
  const page = (direction: number) => row.current?.scrollBy({ left: direction * row.current.clientWidth * 0.8, behavior: "smooth" });

  return (
    <Box sx={{ position: "relative", "&:hover .shelf-arrow": { opacity: 1 } }}>
      <Box
        ref={row}
        onScroll={update}
        sx={{ display: "flex", gap: 2, overflowX: "auto", px: inset, "&::-webkit-scrollbar": { display: "none" }, scrollbarWidth: "none" }}
      >
        {books.map((e) => <ShelfBook key={e.book.id} entry={e} numbered={numbered} onOpen={() => onOpen(e)} />)}
      </Box>
      {!edges.start && <ShelfArrow side="left" onClick={() => page(-1)}><ChevronLeftRoundedIcon /></ShelfArrow>}
      {!edges.end && <ShelfArrow side="right" onClick={() => page(1)}><ChevronRightRoundedIcon /></ShelfArrow>}
    </Box>
  );
}

function ShelfArrow({ side, onClick, children }: { side: "left" | "right"; onClick: () => void; children: ReactNode }) {
  const theme = useTheme();
  return (
    <IconButton
      className="shelf-arrow"
      onClick={(e) => { e.stopPropagation(); onClick(); }}
      aria-label={side === "left" ? "previous" : "next"}
      sx={{
        position: "absolute", top: { xs: 64 - 20, md: 76 - 20 }, [side]: 4, opacity: 0, transition: "opacity 160ms", display: { xs: "none", md: "inline-flex" },
        bgcolor: alpha(theme.palette.surface.highest, 0.9), boxShadow: 3, "&:hover": { bgcolor: theme.palette.surface.highest },
      }}
    >
      {children}
    </IconButton>
  );
}

function ShelfBook({ entry, numbered, onOpen }: { entry: LibraryEntryDto; numbered: boolean; onOpen: () => void }) {
  const player = usePlayer();
  const remaining = useRemaining();
  const { book } = entry;
  const number = book.metadata.seriesIndex;
  return (
    <Box
      onClick={(e) => { e.stopPropagation(); onOpen(); }}
      sx={{ width: SHELF_COVER, flexShrink: 0, cursor: "pointer", "&:hover .play": { opacity: 1, transform: "none" }, "&:hover .cover": { transform: "translateY(-3px)" } }}
    >
      <Box sx={{ position: "relative" }}>
        <Box className="cover" sx={{ transition: "transform 160ms ease" }}>
          <BookCover book={book} size={256} progress={isStarted(entry.playback) ? progressOf(entry) : undefined} shadow />
        </Box>
        {numbered && number && (
          <Box sx={{ position: "absolute", top: 8, left: 8, px: 1, py: 0.25, borderRadius: 999, bgcolor: "rgba(0,0,0,0.55)", color: "#fff", fontSize: 12, fontWeight: 700 }}>
            #{number}
          </Box>
        )}
        <PlayButton
          size={40}
          onClick={(e) => { e.stopPropagation(); player.play(entry); }}
          className="play"
          sx={{ position: "absolute", right: 8, bottom: 16, opacity: 0, transform: "translateY(6px)", transition: "opacity 160ms, transform 160ms", display: { xs: "none", md: "inline-flex" } }}
        />
      </Box>
      <Typography variant="body2" sx={{ mt: 1, fontWeight: 700, lineHeight: 1.3, display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden" }}>
        {book.metadata.title}
      </Typography>
      {!numbered && book.metadata.author && (
        <Typography variant="caption" color="text.secondary" noWrap component="div">{book.metadata.author}</Typography>
      )}
      <Typography variant="caption" color="text.secondary" noWrap component="div" sx={{ opacity: 0.8, fontWeight: 600 }}>{remaining(entry)}</Typography>
    </Box>
  );
}

/**
 * A series in the library, as on the phone: its emblem, name, authors and overall progress above
 * a shelf ordered by book number. Clicking the card outside its books opens the series' page.
 */
export function SeriesCard({ series, books, onOpenSeries, onOpenBook }: { series: string; books: LibraryEntryDto[]; onOpenSeries: () => void; onOpenBook: (e: LibraryEntryDto) => void }) {
  const { t } = useTranslation();
  const theme = useTheme();
  const line = useSeriesLine();
  const progress = seriesStats(books).progress;
  return (
    <Box
      onClick={onOpenSeries}
      sx={{
        position: "relative", minWidth: 0, borderRadius: "24px", cursor: "pointer", overflow: "hidden", bgcolor: "surface.low",
        border: 1, borderColor: alpha(theme.palette.text.primary, 0.06), transition: "border-color 160ms",
        "&:hover": { borderColor: alpha(theme.palette.text.primary, 0.14) },
      }}
    >
      <CoverBackdrop book={books[0].book} contained intensity={0.45} />
      <Box sx={{ position: "relative", py: 2.5 }}>
        <ButtonBase
          component="div"
          onClick={(e) => { e.stopPropagation(); onOpenSeries(); }}
          aria-label={t("library.openSeries")}
          sx={{ display: "flex", alignItems: "center", gap: 1.5, px: 2.5, width: "100%", justifyContent: "flex-start", textAlign: "left" }}
        >
          <StackedCovers books={books} />
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography sx={{ fontWeight: 800, fontSize: 20, lineHeight: 1.25, display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden" }}>{series}</Typography>
            <Typography variant="caption" color="text.secondary" noWrap component="div">{line(books)}</Typography>
          </Box>
          <FinishedPill books={books} />
          <KeyboardArrowRightRoundedIcon sx={{ color: "text.secondary", flexShrink: 0 }} />
        </ButtonBase>
        {progress > 0 && <ProgressLine value={progress} height={3} sx={{ mx: 2.5, mt: 2 }} />}
        <Box sx={{ mt: 2.5 }}>
          <Shelf books={books} numbered onOpen={onOpenBook} inset={2.5} />
        </Box>
      </Box>
    </Box>
  );
}
