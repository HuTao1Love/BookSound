import BedtimeIcon from "@mui/icons-material/Bedtime";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import FastForwardIcon from "@mui/icons-material/FastForward";
import FastRewindIcon from "@mui/icons-material/FastRewind";
import GraphicEqIcon from "@mui/icons-material/GraphicEq";
import PauseCircleIcon from "@mui/icons-material/PauseCircle";
import PlayCircleIcon from "@mui/icons-material/PlayCircle";
import SkipNextIcon from "@mui/icons-material/SkipNext";
import SkipPreviousIcon from "@mui/icons-material/SkipPrevious";
import SpeedIcon from "@mui/icons-material/Speed";
import {
  Alert, Box, Button, Chip, IconButton, List, ListItemButton, ListItemText, Menu, MenuItem, Slider, Stack, Typography,
} from "@mui/material";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import BookCover from "../components/BookCover";
import { formatDuration } from "../library";
import { voicePresets } from "../player/equalizer";
import { usePlayer } from "../player/PlayerContext";

const SPEEDS = [0.75, 0.9, 1, 1.1, 1.2, 1.3, 1.5, 1.75, 2, 2.5, 3];
const SLEEP_MINUTES = [5, 10, 15, 30, 45, 60, 90];

export default function PlayerPage() {
  const { t } = useTranslation();
  const player = usePlayer();
  const navigate = useNavigate();
  const [menu, setMenu] = useState<{ kind: "speed" | "sleep" | "voice"; anchor: HTMLElement } | null>(null);
  const [showChapters, setShowChapters] = useState(false);
  const [dragging, setDragging] = useState<number | null>(null);
  const [, tick] = useState(0);

  // The sleep timer's countdown.
  useEffect(() => {
    if (player.sleep.kind !== "minutes") return;
    const id = window.setInterval(() => tick((n) => n + 1), 1000);
    return () => window.clearInterval(id);
  }, [player.sleep]);

  const book = player.book;
  if (!book) {
    return (
      <Box sx={{ textAlign: "center", py: 8 }}>
        <Typography color="text.secondary">{t("player.nothing")}</Typography>
        <Button onClick={() => navigate("/")}>{t("nav.library")}</Button>
      </Box>
    );
  }
  const chapters = book.chapters ?? [];
  const chapter = chapters[player.chapterIndex];
  // Within a chapter when the book has several: that's what one listens to at a time.
  const start = chapters.length > 1 && chapter ? chapter.startMs : 0;
  const end = chapters.length > 1 && chapter ? chapter.endMs : player.durationMs;
  const position = dragging ?? player.positionMs;
  const sleepLeft = player.sleep.kind === "minutes" ? player.sleep.endsAt - Date.now() : 0;

  return (
    <Stack spacing={2} sx={{ maxWidth: 560, mx: "auto", alignItems: "stretch" }}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <IconButton onClick={() => navigate(-1)} aria-label="close"><ExpandMoreIcon /></IconButton>
        <Typography variant="body2" color="text.secondary" noWrap sx={{ flex: 1, textAlign: "center", cursor: "pointer" }} onClick={() => navigate(`/book/${book.id}`)}>
          {book.metadata.author}
        </Typography>
        <Box sx={{ width: 40 }} />
      </Box>
      {player.remoteOffer && (
        <Alert severity="info" action={<><Button onClick={player.acceptRemote}>{t("player.jump")}</Button><Button onClick={player.dismissRemote}>{t("common.no")}</Button></>}>
          {t("player.otherDevice", { time: formatDuration(player.remoteOffer.positionMs) })}
        </Alert>
      )}
      <Box sx={{ width: "min(100%, 360px)", mx: "auto" }}>
        <BookCover book={book} size={720} radius={3} />
      </Box>
      <Box sx={{ textAlign: "center" }}>
        <Typography variant="h6" sx={{ fontWeight: 600 }}>{book.metadata.title}</Typography>
        {chapter && chapters.length > 1 && (
          <Typography color="text.secondary" sx={{ cursor: "pointer" }} onClick={() => setShowChapters(!showChapters)}>
            {chapter.title} · {t("player.chapter", { n: player.chapterIndex + 1, total: chapters.length })}
          </Typography>
        )}
      </Box>
      <Box>
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
      <Stack direction="row" sx={{ justifyContent: "center", alignItems: "center" }} spacing={1}>
        <IconButton onClick={() => player.goToChapter(player.chapterIndex - 1)} disabled={player.chapterIndex === 0} aria-label="previous chapter"><SkipPreviousIcon /></IconButton>
        <IconButton onClick={() => player.skip(-player.skipBack)} aria-label={t("player.back", { s: player.skipBack })} size="large"><FastRewindIcon fontSize="large" /></IconButton>
        <IconButton onClick={player.toggle} color="primary" aria-label={player.playing ? "pause" : "play"} sx={{ p: 0 }}>
          {player.playing ? <PauseCircleIcon sx={{ fontSize: 80 }} /> : <PlayCircleIcon sx={{ fontSize: 80 }} />}
        </IconButton>
        <IconButton onClick={() => player.skip(player.skipForward)} aria-label={t("player.forward", { s: player.skipForward })} size="large"><FastForwardIcon fontSize="large" /></IconButton>
        <IconButton onClick={() => player.goToChapter(player.chapterIndex + 1)} disabled={player.chapterIndex >= chapters.length - 1} aria-label="next chapter"><SkipNextIcon /></IconButton>
      </Stack>
      <Stack direction="row" spacing={1} sx={{ justifyContent: "center", flexWrap: "wrap", gap: 1 }}>
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

      {showChapters && chapters.length > 1 && (
        <List dense>
          {chapters.map((c, i) => (
            <ListItemButton key={c.index} selected={i === player.chapterIndex} onClick={() => player.goToChapter(i)}>
              <ListItemText primary={c.title} />
              <Typography variant="body2" color="text.secondary">{formatDuration(c.endMs - c.startMs)}</Typography>
            </ListItemButton>
          ))}
        </List>
      )}
    </Stack>
  );
}
