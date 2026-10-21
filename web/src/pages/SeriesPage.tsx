import ArrowBackRoundedIcon from "@mui/icons-material/ArrowBackRounded";
import LibraryBooksRoundedIcon from "@mui/icons-material/LibraryBooksRounded";
import PauseRoundedIcon from "@mui/icons-material/PauseRounded";
import PlayArrowRoundedIcon from "@mui/icons-material/PlayArrowRounded";
import { alpha, Box, Button, ButtonBase, CircularProgress, IconButton, Stack, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";
import { useNavigate, useParams } from "react-router-dom";
import { useBooks } from "../api/hooks";
import type { LibraryEntryDto } from "../api/types.gen";
import BookCover, { CoverBackdrop } from "../components/BookCover";
import { FinishedPill, StackedCovers, useRemaining, useSeriesLine } from "../components/SeriesParts";
import { EmptyState, ProgressLine } from "../components/Ui";
import { isStarted, progressOf, seriesBooks, seriesStats, upNext } from "../library";
import { usePlayer } from "../player/PlayerContext";

/** All books of one series, as on the phone: the emblem and totals, a button that continues the series, the books in order. */
export default function SeriesPage() {
  const { t } = useTranslation();
  const { name } = useParams();
  const books = useBooks();
  const navigate = useNavigate();
  const player = usePlayer();
  const line = useSeriesLine();
  if (books.isLoading) return <CircularProgress sx={{ display: "block", mx: "auto", mt: 8 }} />;
  const entries = seriesBooks(books.data ?? [], decodeURIComponent(name ?? ""));
  const back = <IconButton onClick={() => navigate(-1)} aria-label="back" sx={{ alignSelf: "flex-start", ml: -1, bgcolor: "action.hover" }}><ArrowBackRoundedIcon /></IconButton>;
  if (entries.length === 0) {
    return <Stack spacing={2}>{back}<EmptyState icon={<LibraryBooksRoundedIcon />} title={t("library.seriesEmpty")} /></Stack>;
  }
  const series = entries[0].book.metadata.series!;
  const next = upNext(entries);
  const progress = seriesStats(entries).progress;

  return (
    <Box>
      <CoverBackdrop book={entries[0].book} />
      <Stack spacing={0.5} sx={{ position: "relative", alignItems: "center" }}>
        {back}
        <Stack sx={{ alignItems: "center", textAlign: "center", width: "100%", maxWidth: 720, pb: 3 }}>
          <StackedCovers books={entries} size={120} />
          <Typography variant="h4" sx={{ mt: 3, fontSize: { xs: 28, md: 34 }, lineHeight: 1.15 }}>{series}</Typography>
          <Typography color="text.secondary" sx={{ mt: 0.75 }}>{line(entries)}</Typography>
          <Box sx={{ mt: 1.5, "&:empty": { display: "none" } }}><FinishedPill books={entries} /></Box>
          {progress > 0 && <ProgressLine value={progress} sx={{ mt: 2, width: "100%", maxWidth: 420 }} />}
          {next && (
            <>
              <Button variant="contained" size="large" startIcon={<PlayArrowRoundedIcon />} onClick={() => player.play(next)} sx={{ mt: 3, px: 4 }}>
                {isStarted(next.playback) ? t("book.resume") : t("book.play")}
              </Button>
              <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden" }}>
                {t("library.upNext", { title: numbered(next) })}
              </Typography>
            </>
          )}
        </Stack>
        {entries.map((e) => <SeriesRow key={e.book.id} entry={e} onOpen={() => navigate(`/book/${e.book.id}`)} />)}
      </Stack>
    </Box>
  );
}

const numbered = (e: LibraryEntryDto) => (e.book.metadata.seriesIndex ? `#${e.book.metadata.seriesIndex}. ` : "") + e.book.metadata.title;

function SeriesRow({ entry, onOpen }: { entry: LibraryEntryDto; onOpen: () => void }) {
  const player = usePlayer();
  const remaining = useRemaining();
  const { book } = entry;
  const isCurrent = player.book?.id === book.id;
  return (
    <ButtonBase
      component="div"
      onClick={onOpen}
      sx={{ width: "100%", maxWidth: 720, display: "flex", gap: 2, p: 1, borderRadius: 4, justifyContent: "flex-start", textAlign: "left", "&:hover": { bgcolor: "action.hover" } }}
    >
      <Box sx={{ width: 80, flexShrink: 0 }}>
        <BookCover book={book} size={256} radius={1.3} progress={isStarted(entry.playback) ? progressOf(entry) : undefined} />
      </Box>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography sx={{ fontWeight: 800, fontSize: 17, lineHeight: 1.3, display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden" }}>
          {book.metadata.seriesIndex && <Box component="span" sx={{ color: "primary.main" }}>#{book.metadata.seriesIndex}. </Box>}
          {book.metadata.title}
        </Typography>
        {book.metadata.author && <Typography variant="body2" color="text.secondary" noWrap>{book.metadata.author}</Typography>}
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5, fontWeight: 700 }}>{remaining(entry)}</Typography>
      </Box>
      <IconButton
        aria-label={isCurrent && player.playing ? "pause" : "play"}
        onClick={(e) => { e.stopPropagation(); if (isCurrent) player.toggle(); else player.play(entry); }}
        sx={{ alignSelf: "center", width: 48, height: 48, color: "primary.main", bgcolor: (t) => alpha(t.palette.primary.main, 0.14), "&:hover": { bgcolor: (t) => alpha(t.palette.primary.main, 0.22) } }}
      >
        {isCurrent && player.playing ? <PauseRoundedIcon /> : <PlayArrowRoundedIcon />}
      </IconButton>
    </ButtonBase>
  );
}
