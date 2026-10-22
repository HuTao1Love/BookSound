import { Box, Button, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";
import { Navigate, useNavigate } from "react-router-dom";
import { CoverBackdrop } from "../components/BookCover";
import NowPlaying, { useSidePlayer } from "../components/NowPlaying";
import { usePlayer } from "../player/PlayerContext";

/** The player on narrow screens; wide ones have it on the right, so this page shows the book instead. */
export default function PlayerPage() {
  const { t } = useTranslation();
  const player = usePlayer();
  const navigate = useNavigate();
  const side = useSidePlayer();

  const book = player.book;
  if (!book) {
    return (
      <Box sx={{ textAlign: "center", py: 8 }}>
        <Typography color="text.secondary">{t("player.nothing")}</Typography>
        <Button onClick={() => navigate("/")}>{t("nav.library")}</Button>
      </Box>
    );
  }
  if (side) return <Navigate to={`/book/${book.id}`} replace />;
  return (
    <Box>
      <CoverBackdrop book={book} />
      <Box sx={{ position: "relative", maxWidth: 520, mx: "auto" }}>
        <NowPlaying />
      </Box>
    </Box>
  );
}
