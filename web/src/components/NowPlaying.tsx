import BedtimeIcon from "@mui/icons-material/Bedtime";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import FastForwardIcon from "@mui/icons-material/FastForward";
import FastRewindIcon from "@mui/icons-material/FastRewind";
import GraphicEqIcon from "@mui/icons-material/GraphicEq";
import SkipNextIcon from "@mui/icons-material/SkipNext";
import SkipPreviousIcon from "@mui/icons-material/SkipPrevious";
import SpeedIcon from "@mui/icons-material/Speed";
import {
  Box, Chip, IconButton, List, ListItemButton, ListItemText, Menu, MenuItem, Slider, Stack, Typography, useMediaQuery, useTheme,
} from "@mui/material";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { formatDuration } from "../library";
import { voicePresets } from "../player/equalizer";
import { usePlayer } from "../player/PlayerContext";
import BookCover, { CoverBackdrop } from "./BookCover";
import { PlayButton } from "./Ui";

const SPEEDS = [0.75, 0.9, 1, 1.1, 1.2, 1.3, 1.5, 1.75, 2, 2.5, 3];
const SLEEP_MINUTES = [5, 10, 15, 30, 45, 60, 90];

/** Width of the player on the right of wide screens. */
export const SIDE_PLAYER_WIDTH = 380;

/** Whether the screen is wide enough for the player on the right. */
export function useSidePlayerFits(): boolean {
  const theme = useTheme();
  return useMediaQuery(theme.breakpoints.up("lg"));
}

/** Whether the player sits on the right of the screen (a wide screen with a book loaded) instead of its own page. */
export function useSidePlayer(): boolean {
  const fits = useSidePlayerFits();
  return fits && !!usePlayer().book;
}

/** The player on the right of wide screens: always there while a book is loaded, with its chapters below. */
export function SidePlayer() {
  const book = usePlayer().book;
  if (!book) return null;
  return (
    <Box
      component="aside"
      sx={{
        position: "fixed", top: 0, right: 0, bottom: 0, width: SIDE_PLAYER_WIDTH, zIndex: 5, overflowY: "auto", overflowX: "hidden",
        bgcolor: "surface.low", borderLeft: 1, borderColor: "divider",
      }}
    >
      <CoverBackdrop book={book} panel />
      <Box sx={{ position: "relative", px: 3, pt: 3, pb: 4 }}>
        <NowPlaying side />
      </Box>
    </Box>
  );
}

/** Cover, position, controls, speed, sleep timer, voice and chapters of the book playing. */
export default function NowPlaying({ side = false }: { side?: boolean }) {
  const { t } = useTranslation();
  const player = usePlayer();
  const navigate = useNavigate();
  const [menu, setMenu] = useState<{ kind: "speed" | "sleep" | "voice"; anchor: HTMLElement } | null>(null);
  const [showChapters, setShowChapters] = useState(false);
  const [dragging, setDragging] = useState<number | null>(null);
  const [, tick] = useState(0);
  const current = useRef<HTMLDivElement>(null);

  // The sleep timer's countdown.
  useEffect(() => {
    if (player.sleep.kind !== "minutes") return;
    const id = window.setInterval(() => tick((n) => n + 1), 1000);
    return () => window.clearInterval(id);
  }, [player.sleep]);

  // The chapter playing stays in view in the side player's list.
  useEffect(() => {
    if (side) current.current?.scrollIntoView({ block: "nearest" });
  }, [side, player.chapterIndex, player.book?.id]);

  const book = player.book;
  if (!book) return null;
  const chapters = book.chapters ?? [];
  const chapter = chapters[player.chapterIndex];
  // Within a chapter when the book has several: that's what one listens to at a time.
  const start = chapters.length > 1 && chapter ? chapter.startMs : 0;
  const end = chapters.length > 1 && chapter ? chapter.endMs : player.durationMs;
  const position = dragging ?? player.positionMs;
  const sleepLeft = player.sleep.kind === "minutes" ? player.sleep.endsAt - Date.now() : 0;
  const chaptersShown = chapters.length > 1 && (side || showChapters);

  return (
    <Stack spacing={side ? 2 : 2.5} sx={{ alignItems: "stretch" }}>
      <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
        {!side && <IconButton onClick={() => navigate(-1)} aria-label="close" sx={{ bgcolor: "action.hover" }}><ExpandMoreIcon /></IconButton>}
        <Typography
          variant="overline"
          color="text.secondary"
          noWrap
          sx={{ flex: 1, textAlign: side ? "left" : "center", cursor: side ? undefined : "pointer" }}
          onClick={side ? undefined : () => navigate(`/book/${book.id}`)}
        >
          {side ? t("player.nowPlaying") : book.metadata.series ?? book.metadata.author}
        </Typography>
        {!side && <Box sx={{ width: 40 }} />}
      </Box>
      <Box
        onClick={side ? () => navigate(`/book/${book.id}`) : undefined}
        sx={{ width: side ? "100%" : "min(100%, 380px)", mx: "auto", cursor: side ? "pointer" : undefined }}
      >
        <BookCover book={book} size={720} radius={side ? 3 : 4} shadow />
      </Box>
      <Box sx={{ textAlign: "center" }}>
        <Typography
          variant={side ? "h6" : "h5"}
          onClick={side ? () => navigate(`/book/${book.id}`) : undefined}
          sx={{ lineHeight: 1.25, fontWeight: 800, cursor: side ? "pointer" : undefined, "&:hover": side ? { color: "primary.main" } : undefined }}
        >
          {book.metadata.title}
        </Typography>
        {book.metadata.author && <Typography color="text.secondary" sx={{ fontWeight: 600, mt: 0.5 }}>{book.metadata.author}</Typography>}
        {chapter && chapters.length > 1 && (
          <Typography
            variant="body2"
            color="primary"
            sx={{ cursor: side ? undefined : "pointer", mt: 1, fontWeight: 700 }}
            onClick={side ? undefined : () => setShowChapters(!showChapters)}
          >
            {chapter.title} · {t("player.chapter", { n: player.chapterIndex + 1, total: chapters.length })}
          </Typography>
        )}
      </Box>
      <Box sx={{ px: 0.5 }}>
        <Slider
          min={start}
          max={Math.max(end, start + 1)}
          value={Math.min(Math.max(position, start), end)}
          onChange={(_, v) => setDragging(v as number)}
          onChangeCommitted={(_, v) => { player.seek(v as number); setDragging(null); }}
          aria-label="position"
        />
        <Box sx={{ display: "flex", justifyContent: "space-between", mt: -1 }}>
          <Typography variant="caption" color="text.secondary">{formatDuration(position - start)}</Typography>
          <Typography variant="caption" color="text.secondary">
            {chapters.length > 1 ? `${formatDuration(player.durationMs - position, false)} · ` : ""}-{formatDuration(end - position)}
          </Typography>
        </Box>
      </Box>
      <Stack direction="row" sx={{ justifyContent: "center", alignItems: "center" }} spacing={side ? 0.5 : 1}>
        <IconButton onClick={() => player.goToChapter(player.chapterIndex - 1)} disabled={player.chapterIndex === 0} aria-label="previous chapter"><SkipPreviousIcon /></IconButton>
        <IconButton onClick={() => player.skip(-player.skipBack)} aria-label={t("player.back", { s: player.skipBack })} size="large"><FastRewindIcon fontSize="large" /></IconButton>
        <PlayButton size={side ? 68 : 80} playing={player.playing} onClick={player.toggle} sx={{ mx: 1 }} />
        <IconButton onClick={() => player.skip(player.skipForward)} aria-label={t("player.forward", { s: player.skipForward })} size="large"><FastForwardIcon fontSize="large" /></IconButton>
        <IconButton onClick={() => player.goToChapter(player.chapterIndex + 1)} disabled={player.chapterIndex >= chapters.length - 1} aria-label="next chapter"><SkipNextIcon /></IconButton>
      </Stack>
      <Stack direction="row" sx={{ justifyContent: "center", flexWrap: "wrap", gap: 1 }}>
        <Chip icon={<SpeedIcon />} label={`${player.speed}×`} onClick={(e) => setMenu({ kind: "speed", anchor: e.currentTarget })} />
        <Chip
          icon={<BedtimeIcon />}
          color={player.sleep.kind === "off" ? "default" : "primary"}
          label={player.sleep.kind === "minutes" ? formatDuration(sleepLeft) : player.sleep.kind === "chapter" ? t("player.sleepEndOfChapter") : t("player.sleep")}
          onClick={(e) => setMenu({ kind: "sleep", anchor: e.currentTarget })}
        />
        <Chip
          icon={<GraphicEqIcon />}
          color={player.preset === "OFF" ? "default" : "primary"}
          label={player.preset === "OFF" ? t("player.voice") : t(`player.presets.${player.preset}`)}
          onClick={(e) => setMenu({ kind: "voice", anchor: e.currentTarget })}
        />
      </Stack>

      <Menu open={!!menu} anchorEl={menu?.anchor} onClose={() => setMenu(null)}>
        {menu?.kind === "speed" && SPEEDS.map((s) => (
          <MenuItem key={s} selected={s === player.speed} onClick={() => { player.setSpeed(s); setMenu(null); }}>{s}×</MenuItem>
        ))}
        {menu?.kind === "sleep" && [
          <MenuItem key="off" onClick={() => { player.setSleep({ kind: "off" }); setMenu(null); }}>{t("player.sleepOff")}</MenuItem>,
          <MenuItem key="chapter" onClick={() => { player.setSleep({ kind: "chapter" }); setMenu(null); }}>{t("player.sleepEndOfChapter")}</MenuItem>,
          ...SLEEP_MINUTES.map((m) => (
            <MenuItem key={m} onClick={() => { player.setSleep({ kind: "minutes", endsAt: Date.now() + m * 60_000 }); setMenu(null); }}>
              {t("player.sleepMinutes", { count: m })}
            </MenuItem>
          )),
        ]}
        {menu?.kind === "voice" && voicePresets.map((p) => (
          <MenuItem key={p} selected={p === player.preset} onClick={() => { player.setPreset(p); setMenu(null); }}>{t(`player.presets.${p}`)}</MenuItem>
        ))}
      </Menu>

      {chaptersShown && (
        <Box sx={{ pt: side ? 1 : 0 }}>
          {side && <Typography variant="overline" color="text.secondary" component="div" sx={{ mb: 0.5 }}>{t("player.chapters")}</Typography>}
          <List dense disablePadding sx={side ? undefined : { bgcolor: "background.paper", border: 1, borderColor: "divider", borderRadius: 5, p: 1 }}>
            {chapters.map((c, i) => (
              <ListItemButton key={c.index} ref={i === player.chapterIndex ? current : undefined} selected={i === player.chapterIndex} onClick={() => player.goToChapter(i)}>
                <ListItemText primary={c.title} slotProps={{ primary: { noWrap: true, sx: { fontWeight: i === player.chapterIndex ? 700 : 500 } } }} />
                <Typography variant="body2" color="text.secondary" sx={{ pl: 1, flexShrink: 0 }}>{formatDuration(c.endMs - c.startMs)}</Typography>
              </ListItemButton>
            ))}
          </List>
        </Box>
      )}
    </Stack>
  );
}
