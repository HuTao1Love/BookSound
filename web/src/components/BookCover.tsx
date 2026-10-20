import HeadphonesIcon from "@mui/icons-material/Headphones";
import { Box } from "@mui/material";
import { useState } from "react";
import { paths } from "../api/client";
import type { BookDto } from "../api/types.gen";

/** A square cover, or a tinted placeholder with the title's initial when the book has none. */
export default function BookCover({ book, size = 256, radius = 1.5, progress }: { book: BookDto; size?: number; radius?: number; progress?: number }) {
  const [failed, setFailed] = useState(false);
  const hue = [...book.id].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 0);
  return (
    <Box sx={{ position: "relative", width: "100%", aspectRatio: "1", borderRadius: radius, overflow: "hidden", bgcolor: `hsl(${hue} 35% 45%)` }}>
      {book.coverSha256 && !failed ? (
        <Box
          component="img"
          src={`${paths.bookCover(book.id, size)}&v=${book.coverSha256.slice(0, 8)}`}
          alt=""
          loading="lazy"
          onError={() => setFailed(true)}
          sx={{ width: "100%", height: "100%", objectFit: "cover", display: "block" }}
        />
      ) : (
        <Box sx={{ position: "absolute", inset: 0, display: "grid", placeItems: "center", color: "rgba(255,255,255,0.85)" }}>
          <HeadphonesIcon sx={{ fontSize: "40%" }} />
        </Box>
      )}
      {progress !== undefined && progress > 0 && (
        <Box sx={{ position: "absolute", left: 0, right: 0, bottom: 0, height: 4, bgcolor: "rgba(0,0,0,0.35)" }}>
          <Box sx={{ width: `${progress * 100}%`, height: "100%", bgcolor: "primary.main" }} />
        </Box>
      )}
    </Box>
  );
}
