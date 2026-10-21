import PlayArrowIcon from "@mui/icons-material/PlayArrow";
import { Box, Button, CircularProgress, List, ListItemButton, ListItemText, Stack, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";
import { useNavigate, useParams } from "react-router-dom";
import { useBooks } from "../api/hooks";
import BookCover from "../components/BookCover";
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
    <Stack spacing={2}>
      <Box>
        <Typography variant="h5" sx={{ fontWeight: 600 }}>{series}</Typography>
        <Typography color="text.secondary">{entries[0]?.book.metadata.author}</Typography>
      </Box>
      {next && (
        <Button variant="contained" startIcon={<PlayArrowIcon />} onClick={() => player.play(next)} sx={{ alignSelf: "flex-start" }}>
          {t("library.playNext")}: {next.book.metadata.seriesIndex ? `${next.book.metadata.seriesIndex}. ` : ""}{next.book.metadata.title}
        </Button>
      )}
      <List>
        {entries.map((e) => (
          <ListItemButton key={e.book.id} onClick={() => navigate(`/book/${e.book.id}`)} sx={{ gap: 2, borderRadius: 2 }}>
            <Box sx={{ width: 56, flexShrink: 0 }}>
              <BookCover book={e.book} size={128} progress={progressOf(e)} radius={1} />
            </Box>
            <ListItemText
              primary={`${e.book.metadata.seriesIndex ? e.book.metadata.seriesIndex + ". " : ""}${e.book.metadata.title}`}
              secondary={`${formatDuration(e.book.durationMs, false)}${e.playback?.finished ? " · " + t("book.finished") : ""}`}
            />
          </ListItemButton>
        ))}
      </List>
    </Stack>
  );
}
