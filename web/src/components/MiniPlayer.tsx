import PauseIcon from "@mui/icons-material/Pause";
import PlayArrowIcon from "@mui/icons-material/PlayArrow";
import ReplayIcon from "@mui/icons-material/Replay";
import { Box, IconButton, LinearProgress, Paper, Typography } from "@mui/material";
import { useNavigate } from "react-router-dom";
import { usePlayer } from "../player/PlayerContext";
import BookCover from "./BookCover";

export default function MiniPlayer({ bottomOffset }: { bottomOffset: number }) {
  const player = usePlayer();
  const navigate = useNavigate();
  const book = player.book;
  if (!book) return null;
  const chapter = book.chapters?.[player.chapterIndex];
  return (
    <Paper
      elevation={8}
      sx={{
        position: "fixed", left: { xs: 8, md: "50%" }, right: { xs: 8, md: "auto" }, transform: { md: "translateX(-50%)" },
        width: { md: 640 }, bottom: `calc(${bottomOffset + 8}px + env(safe-area-inset-bottom))`, zIndex: 4, borderRadius: 3, overflow: "hidden",
      }}
    >
      <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, p: 1, cursor: "pointer" }} onClick={() => navigate("/player")}>
        <Box sx={{ width: 48, flexShrink: 0 }}>
          <BookCover book={book} size={128} radius={1} />
        </Box>
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography variant="body2" noWrap sx={{ fontWeight: 600 }}>
            {book.metadata.title}
          </Typography>
          <Typography variant="caption" color="text.secondary" noWrap component="div">
            {chapter?.title ?? book.metadata.author}
          </Typography>
        </Box>
        <IconButton onClick={(e) => { e.stopPropagation(); player.skip(-player.skipBack); }} aria-label="back">
          <ReplayIcon />
        </IconButton>
        <IconButton onClick={(e) => { e.stopPropagation(); player.toggle(); }} aria-label={player.playing ? "pause" : "play"} color="primary" size="large">
          {player.playing ? <PauseIcon fontSize="large" /> : <PlayArrowIcon fontSize="large" />}
        </IconButton>
      </Box>
      <LinearProgress variant="determinate" value={player.durationMs ? (player.positionMs / player.durationMs) * 100 : 0} sx={{ height: 3 }} />
    </Paper>
  );
}
