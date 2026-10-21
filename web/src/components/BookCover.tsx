import { Box, useTheme } from "@mui/material";
import { useState } from "react";
import { paths } from "../api/client";
import type { BookDto } from "../api/types.gen";
import { SIDEBAR_WIDTH } from "./Ui";

// The phone's placeholder palettes: a book without a cover looks the same everywhere.
const PALETTES = [
  ["#ffb27d", "#e0607e"],
  ["#8ed8bf", "#3f7fa8"],
  ["#bbb0ff", "#6a55d8"],
  ["#ffd48a", "#e5835a"],
  ["#8cc8f0", "#4a5bc4"],
  ["#f4a3c4", "#8e5bd0"],
];

export function paletteFor(title: string): string[] {
  // Java's String.hashCode, as the phone picks it.
  let h = 0;
  for (let i = 0; i < title.length; i++) h = (Math.imul(h, 31) + title.charCodeAt(i)) | 0;
  return PALETTES[Math.abs(h) % PALETTES.length];
}

export function coverUrl(book: BookDto, size: number): string | null {
  return book.coverSha256 ? `${paths.bookCover(book.id, size)}&v=${book.coverSha256.slice(0, 8)}` : null;
}

/** A square cover with a faint edge, or the title on a duotone gradient when the book has none. */
export default function BookCover({ book, size = 256, radius = 2, progress, shadow }: { book: BookDto; size?: number; radius?: number; progress?: number; shadow?: boolean }) {
  const theme = useTheme();
  const [failed, setFailed] = useState(false);
  const url = failed ? null : coverUrl(book, size);
  const [light, dark] = paletteFor(book.metadata.title);
  return (
    <Box
      sx={{
        position: "relative", width: "100%", aspectRatio: "1", borderRadius: radius, overflow: "hidden", containerType: "inline-size",
        background: `linear-gradient(135deg, ${light}, ${dark})`,
        boxShadow: shadow ? "0 18px 50px -12px rgba(0,0,0,0.55)" : undefined,
        "&::after": {
          content: '""', position: "absolute", inset: 0, borderRadius: "inherit", pointerEvents: "none",
          boxShadow: `inset 0 0 0 1px ${theme.palette.mode === "dark" ? "rgba(255,255,255,0.08)" : "rgba(0,0,0,0.06)"}`,
        },
      }}
    >
      {url ? (
        <Box component="img" src={url} alt="" loading="lazy" onError={() => setFailed(true)} sx={{ width: "100%", height: "100%", objectFit: "cover", display: "block" }} />
      ) : (
        <Box sx={{ position: "absolute", inset: 0, p: "8cqw", display: "flex", flexDirection: "column", justifyContent: "flex-end", color: "rgba(20,12,10,0.88)" }}>
          <Box sx={{ fontWeight: 800, fontSize: "clamp(10px, 11cqw, 34px)", lineHeight: 1.1, letterSpacing: "-0.01em", display: "-webkit-box", WebkitLineClamp: 4, WebkitBoxOrient: "vertical", overflow: "hidden", wordBreak: "break-word" }}>
            {book.metadata.title}
          </Box>
          {book.metadata.author && (
            <Box sx={{ mt: "3cqw", fontWeight: 600, fontSize: "clamp(8px, 6.5cqw, 18px)", opacity: 0.75, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", "@container (max-width: 90px)": { display: "none" } }}>
              {book.metadata.author}
            </Box>
          )}
        </Box>
      )}
      {progress !== undefined && progress > 0 && (
        <Box sx={{ position: "absolute", left: "6%", right: "6%", bottom: "6%", height: 4, borderRadius: 4, bgcolor: "rgba(0,0,0,0.45)", overflow: "hidden" }}>
          <Box sx={{ width: `${progress * 100}%`, height: "100%", background: progress >= 1 ? theme.palette.secondary.main : theme.palette.gradient.css }} />
        </Box>
      )}
    </Box>
  );
}

/** The cover, blurred and fading into the page: behind the book and the player. */
export function CoverBackdrop({ book }: { book: BookDto }) {
  const theme = useTheme();
  const url = coverUrl(book, 256);
  const [light, dark] = paletteFor(book.metadata.title);
  const bg = theme.palette.background.default;
  return (
    <Box aria-hidden sx={{ position: "fixed", top: 0, right: 0, left: { xs: 0, md: SIDEBAR_WIDTH }, height: { xs: 460, md: 560 }, overflow: "hidden", pointerEvents: "none", zIndex: 0 }}>
      {url ? (
        <Box component="img" src={url} alt="" sx={{ position: "absolute", inset: "-10%", width: "120%", height: "120%", objectFit: "cover", filter: "blur(72px) saturate(1.3)", opacity: theme.palette.mode === "dark" ? 0.5 : 0.35 }} />
      ) : (
        <Box sx={{ position: "absolute", inset: 0, background: `radial-gradient(60% 80% at 30% 20%, ${light}55, transparent), radial-gradient(50% 70% at 80% 10%, ${dark}33, transparent)` }} />
      )}
      <Box sx={{ position: "absolute", inset: 0, background: `linear-gradient(to bottom, ${bg}26, ${bg}b3 55%, ${bg})` }} />
    </Box>
  );
}
