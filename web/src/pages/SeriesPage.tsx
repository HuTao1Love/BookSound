import PlayArrowIcon from "@mui/icons-material/PlayArrow";
import ArrowBackRoundedIcon from "@mui/icons-material/ArrowBackRounded";
import { Box, Button, Card, CircularProgress, IconButton, List, ListItemButton, ListItemText, Stack, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";
import { useNavigate, useParams } from "react-router-dom";
import { useBooks } from "../api/hooks";
import BookCover, { CoverBackdrop } from "../components/BookCover";
import { formatDuration, progressOf, seriesBooks } from "../library";
import { usePlayer } from "../player/PlayerContext";

export default function SeriesPage() {
  const { t } = useTranslation();
  const { name } = useParams();
  const books = useBooks();
  const navigate = useNavigate();
  const player = usePlayer();
  if (books.isLoading) return <CircularProgress sx={{ display: "block", mx: "auto", mt: 8 }} />;
  const series = decodeURIComponent(name ?? "");
  const entries = seriesBooks(books.data ?? [], series);
  // The book in progress, else the first one not finished.
  const next = entries.find((e) => e.playback && !e.playback.finished && e.playback.positionMs > 0) ?? entries.find((e) => !e.playback?.finished);

  return (
    <Box>
      {entries[0] && <CoverBackdrop book={entries[0].book} />}
    <Stack spacing={2} sx={{ position: "relative" }}>
      <IconButton onClick={() => navigate(-1)} aria-label="back" sx={{ alignSelf: "flex-start", ml: -1, bgcolor: "action.hover" }}><ArrowBackRoundedIcon /></IconButton>
      <Stack direction="row" spacing={3} sx={{ alignItems: "flex-end" }}>
        {entries[0] && (
          <Box sx={{ width: { xs: 110, md: 160 }, flexShrink: 0 }}>
            <BookCover book={entries[0].book} size={512} radius={3} shadow />
          </Box>
        )}
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="overline" color="text.secondary">{t("library.series")}</Typography>
          <Typography variant="h4" sx={{ fontSize: { xs: 26, md: 36 }, lineHeight: 1.15 }}>{series}</Typography>
          <Typography color="text.secondary" sx={{ mt: 0.5 }}>
            {[entries[0]?.book.metadata.author, t("library.booksInSeries", { count: entries.length })].filter(Boolean).join(" · ")}
          </Typography>
        </Box>
      </Stack>
      {next && (
        <Button variant="contained" size="large" startIcon={<PlayArrowIcon />} onClick={() => player.play(next)} sx={{ alignSelf: "flex-start" }}>
          {t("library.playNext")}: {next.book.metadata.seriesIndex ? `${next.book.metadata.seriesIndex}. ` : ""}{next.book.metadata.title}
        </Button>
      )}
      <Card>
      <List sx={{ p: 1 }}>
        {entries.map((e) => (
          <ListItemButton key={e.book.id} onClick={() => navigate(`/book/${e.book.id}`)} sx={{ gap: 2 }}>
            <Box sx={{ width: 56, flexShrink: 0 }}>
              <BookCover book={e.book} size={128} progress={progressOf(e)} radius={1.5} />
            </Box>
            <ListItemText
              primary={`${e.book.metadata.seriesIndex ? e.book.metadata.seriesIndex + ". " : ""}${e.book.metadata.title}`}
              secondary={`${formatDuration(e.book.durationMs, false)}${e.playback?.finished ? " · " + t("book.finished") : ""}`}
            />
          </ListItemButton>
        ))}
      </List>
      </Card>
    </Stack>
    </Box>
  );
}
