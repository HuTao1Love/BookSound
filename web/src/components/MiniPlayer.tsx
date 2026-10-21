import Replay10RoundedIcon from "@mui/icons-material/Replay10Rounded";
import { alpha, Box, IconButton, Typography, useTheme } from "@mui/material";
import { useNavigate } from "react-router-dom";
import { formatDuration } from "../library";
import { usePlayer } from "../player/PlayerContext";
import BookCover from "./BookCover";
import { PlayButton, ProgressLine, SIDEBAR_WIDTH } from "./Ui";

/** The book playing, above the navigation: opens the player. */
export default function MiniPlayer({ bottomOffset }: { bottomOffset: number }) {
  const player = usePlayer();
  const navigate = useNavigate();
  const theme = useTheme();
  const book = player.book;
  if (!book) return null;
  const chapter = book.chapters?.[player.chapterIndex];
  return (
    <Box sx={{ position: "fixed", left: { xs: 0, md: SIDEBAR_WIDTH }, right: 0, bottom: `calc(${bottomOffset}px + env(safe-area-inset-bottom))`, zIndex: 4, px: { xs: 1, md: 3 }, pb: { xs: 1, md: 2 }, pointerEvents: "none" }}>
      <Box
        onClick={() => navigate("/player")}
        sx={{
          pointerEvents: "auto", maxWidth: 760, mx: "auto", cursor: "pointer", borderRadius: 4, overflow: "hidden",
          bgcolor: alpha(theme.palette.surface.high, 0.88), backdropFilter: "blur(20px) saturate(1.4)",
          border: 1, borderColor: "divider", boxShadow: "0 16px 40px -16px rgba(0,0,0,0.6)",
        }}
      >
        <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, p: 1, pr: 1.5 }}>
          <Box sx={{ width: 52, flexShrink: 0 }}>
            <BookCover book={book} size={128} radius={1.5} />
          </Box>
          <Box sx={{ minWidth: 0, flex: 1 }}>
            <Typography variant="body2" noWrap sx={{ fontWeight: 800 }}>{book.metadata.title}</Typography>
            <Typography variant="caption" color="text.secondary" noWrap component="div">
              {chapter?.title ?? book.metadata.author}
              <Box component="span" sx={{ display: { xs: "none", sm: "inline" } }}> · {formatDuration(player.durationMs - player.positionMs, false)}</Box>
            </Typography>
          </Box>
          <IconButton onClick={(e) => { e.stopPropagation(); player.skip(-player.skipBack); }} aria-label="back">
            <Replay10RoundedIcon />
          </IconButton>
          <PlayButton size={44} playing={player.playing} onClick={(e) => { e.stopPropagation(); player.toggle(); }} />
        </Box>
        <ProgressLine value={player.durationMs ? player.positionMs / player.durationMs : 0} height={3} sx={{ borderRadius: 0 }} />
      </Box>
    </Box>
  );
}
