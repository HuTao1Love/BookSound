import DeleteIcon from "@mui/icons-material/Delete";
import DownloadIcon from "@mui/icons-material/Download";
import EditIcon from "@mui/icons-material/Edit";
import ImageIcon from "@mui/icons-material/Image";
import ArrowBackRoundedIcon from "@mui/icons-material/ArrowBackRounded";
import CheckCircleRoundedIcon from "@mui/icons-material/CheckCircleRounded";
import CloseRoundedIcon from "@mui/icons-material/CloseRounded";
import GraphicEqRoundedIcon from "@mui/icons-material/GraphicEqRounded";
import MoreHorizRoundedIcon from "@mui/icons-material/MoreHorizRounded";
import PlayArrowRoundedIcon from "@mui/icons-material/PlayArrowRounded";
import ReplayIcon from "@mui/icons-material/Replay";
import TravelExploreRoundedIcon from "@mui/icons-material/TravelExploreRounded";
import WatchIcon from "@mui/icons-material/Watch";
import {
  Alert, Box, Button, Card, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, IconButton, List, ListItemButton, ListItemIcon, ListItemText,
  Menu, MenuItem, Snackbar, Stack, TextField, Tooltip, Typography,
} from "@mui/material";
import { useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate, useParams } from "react-router-dom";
import { api, paths, request } from "../api/client";
import { keys, useBook, useMe, useStatus, useWatchProfiles } from "../api/hooks";
import type { BookDto, MetadataDto, WatchFileStatusDto } from "../api/types.gen";
import BookCover, { CoverBackdrop } from "../components/BookCover";
import { MetadataSearchDialog } from "../components/MetadataSearch";
import { useSidePlayerFits } from "../components/NowPlaying";
import { ProgressLine, SectionTitle } from "../components/Ui";
import { chapterAt, formatDuration, isStarted } from "../library";
import { usePlayer } from "../player/PlayerContext";

export default function BookPage() {
  const { t } = useTranslation();
  const { id = "" } = useParams();
  const entry = useBook(id);
  const me = useMe();
  const player = usePlayer();
  // Wide screens have the player on the right: starting a book keeps the page.
  const side = useSidePlayerFits();
  const client = useQueryClient();
  const navigate = useNavigate();
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [more, setMore] = useState<HTMLElement | null>(null);
  const [error, setError] = useState<string | null>(null);
  const coverInput = useRef<HTMLInputElement>(null);

  if (entry.isLoading) return <CircularProgress sx={{ display: "block", mx: "auto", mt: 8 }} />;
  if (!entry.data) return <Alert severity="error">{t("common.error")}</Alert>;
  const { book, playback } = entry.data;
  const meta = book.metadata;
  const isCurrent = player.book?.id === book.id;
  const position = isCurrent ? player.positionMs : playback?.positionMs ?? 0;
  const currentChapter = chapterAt(book.chapters ?? [], position);

  const refresh = (updated?: BookDto) => {
    if (updated) client.setQueryData(keys.book(id), { ...entry.data, book: updated });
    void client.invalidateQueries({ queryKey: keys.books });
  };

  const changeCover = async (file: File) => {
    try {
      const updated = await request<BookDto>("PUT", `${paths.book(id)}/cover`, file, { headers: { "Content-Type": "application/octet-stream" } });
      refresh(updated);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const progress = book.durationMs ? Math.min(1, position / book.durationMs) : 0;
  const started = isStarted(playback) || isCurrent;
  const facts = [
    meta.narrator && t("book.narrator", { name: meta.narrator }),
    formatDuration(book.durationMs, false),
    meta.year,
    meta.genre,
    (book.chapters?.length ?? 0) > 1 && t("book.chapterCount", { count: book.chapters!.length }),
  ].filter(Boolean) as string[];

  return (
    <Box>
      <CoverBackdrop book={book} />
      <Box sx={{ position: "relative" }}>
        <IconButton onClick={() => navigate(-1)} aria-label="back" sx={{ mb: 2, ml: -1, bgcolor: "action.hover" }}><ArrowBackRoundedIcon /></IconButton>
        {error && <Alert severity="error" onClose={() => setError(null)} sx={{ mb: 2 }}>{error}</Alert>}
        <Stack direction={{ xs: "column", md: "row" }} spacing={{ xs: 3, md: 5 }} sx={{ alignItems: { xs: "center", md: "flex-end" } }}>
          <Box sx={{ width: { xs: "min(72vw, 300px)", md: 300 }, flexShrink: 0 }}>
            <BookCover book={book} size={640} radius={3} shadow />
          </Box>
          <Stack spacing={1.25} sx={{ minWidth: 0, flex: 1, textAlign: { xs: "center", md: "left" }, alignItems: { xs: "center", md: "flex-start" } }}>
            {meta.series && (
              <Chip
                size="small"
                color="primary"
                label={`${meta.series}${meta.seriesIndex ? ` · ${meta.seriesIndex}` : ""}`}
                onClick={() => navigate(`/series/${encodeURIComponent(meta.series!)}`)}
              />
            )}
            <Typography variant="h4" sx={{ fontSize: { xs: 28, md: 40 }, lineHeight: 1.1 }}>{meta.title}</Typography>
            {meta.author && <Typography variant="h6" color="text.secondary" sx={{ fontWeight: 600 }}>{meta.author}</Typography>}
            <Typography variant="body2" color="text.secondary">{facts.join(" · ")}</Typography>
            {started && !playback?.finished && (
              <Box sx={{ width: "100%", maxWidth: 420, pt: 0.5 }}>
                <ProgressLine value={progress} />
                <Typography variant="caption" color="text.secondary">
                  {Math.round(progress * 100)}% · {t("book.left", { time: formatDuration(book.durationMs - position, false) })}
                </Typography>
              </Box>
            )}
            {playback?.finished && <Chip size="small" icon={<CheckCircleRoundedIcon />} label={t("book.finished")} sx={{ color: "secondary.main" }} />}
            <Stack direction="row" spacing={1} sx={{ pt: 1.5, flexWrap: "wrap", gap: 1, justifyContent: { xs: "center", md: "flex-start" }, alignItems: "center" }}>
              <Button variant="contained" size="large" startIcon={<PlayArrowRoundedIcon />} onClick={() => { player.play(entry.data!); if (!side) navigate("/player"); }}>
                {started ? t("book.resume") : t("book.play")}
              </Button>
              {started && (
                <Button variant="outlined" size="large" startIcon={<ReplayIcon />} onClick={() => { player.play(entry.data!, 0); if (!side) navigate("/player"); }}>{t("book.restart")}</Button>
              )}
              <Tooltip title={t("book.downloadFile")}>
                <IconButton href={`${paths.bookFile(id)}?download=1`} sx={{ border: 1, borderColor: "divider" }}><DownloadIcon /></IconButton>
              </Tooltip>
              <WatchCopyButton bookId={id} />
              <Tooltip title={t("common.more")}>
                <IconButton onClick={(e) => setMore(e.currentTarget)} sx={{ border: 1, borderColor: "divider" }}><MoreHorizRoundedIcon /></IconButton>
              </Tooltip>
              <Menu open={!!more} anchorEl={more} onClose={() => setMore(null)}>
                <MenuItem onClick={() => { setMore(null); setEditing(true); }}><ListItemIcon><EditIcon fontSize="small" /></ListItemIcon>{t("book.edit")}</MenuItem>
                <MenuItem onClick={() => { setMore(null); coverInput.current?.click(); }}><ListItemIcon><ImageIcon fontSize="small" /></ListItemIcon>{t("book.cover")}</MenuItem>
                {me.data?.user.isAdmin && (
                  <MenuItem onClick={() => { setMore(null); setDeleting(true); }} sx={{ color: "error.main" }}><ListItemIcon><DeleteIcon fontSize="small" color="error" /></ListItemIcon>{t("common.delete")}</MenuItem>
                )}
              </Menu>
              <input ref={coverInput} type="file" accept="image/*" hidden onChange={(e) => e.target.files?.[0] && changeCover(e.target.files[0])} />
            </Stack>
          </Stack>
        </Stack>

        <Box sx={{ display: "grid", gap: 3, mt: { xs: 4, md: 6 }, gridTemplateColumns: { xs: "1fr", lg: meta.description && (book.chapters?.length ?? 0) > 1 ? "minmax(0, 1fr) minmax(0, 1.2fr)" : "1fr" } }}>
          {meta.description && (
            <Box>
              <SectionTitle>{t("book.description")}</SectionTitle>
              <Typography sx={{ whiteSpace: "pre-line", lineHeight: 1.7, color: "text.secondary" }}>{meta.description}</Typography>
            </Box>
          )}
          {(book.chapters?.length ?? 0) > 1 && (
            <Box>
              <SectionTitle>{t("book.chapters")}</SectionTitle>
              <Card>
                <List dense sx={{ p: 1 }}>
                  {book.chapters!.map((c, i) => {
                    const here = started && i === currentChapter;
                    return (
                      <ListItemButton key={c.index} selected={here} onClick={() => player.play(entry.data!, c.startMs)} sx={{ gap: 1.5 }}>
                        <Typography variant="body2" sx={{ width: 28, color: here ? "primary.main" : "text.secondary", fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>
                          {here ? <GraphicEqRoundedIcon fontSize="small" sx={{ verticalAlign: "middle" }} /> : i + 1}
                        </Typography>
                        <ListItemText primary={c.title} slotProps={{ primary: { sx: { fontWeight: here ? 800 : 500 } } }} />
                        <Typography variant="body2" color="text.secondary" sx={{ fontVariantNumeric: "tabular-nums" }}>{formatDuration(c.endMs - c.startMs)}</Typography>
                      </ListItemButton>
                    );
                  })}
                </List>
              </Card>
            </Box>
          )}
        </Box>
      </Box>

      {editing && <EditDialog book={book} onClose={() => setEditing(false)} onSaved={(b) => { setEditing(false); refresh(b); }} />}
      <Dialog open={deleting} onClose={() => setDeleting(false)}>
        <DialogTitle>{t("book.deleteTitle")}</DialogTitle>
        <DialogContent>{t("book.deleteText")}</DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleting(false)}>{t("common.cancel")}</Button>
          <Button color="error" variant="contained" onClick={async () => { await api.delete(paths.book(id)); refresh(); navigate("/"); }}>{t("common.delete")}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

/** Builds the smaller watch copy on the server if needed, then downloads it. */
function WatchCopyButton({ bookId }: { bookId: string }) {
  const { t } = useTranslation();
  const profiles = useWatchProfiles();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchCopy = async (profileId: string) => {
    setAnchor(null);
    setError(null);
    setPreparing(true);
    try {
      // An error answer (no ffmpeg, no such book) or a failed conversion ends the wait.
      for (;;) {
        const status = await api.get<WatchFileStatusDto>(`${paths.watchFile(bookId, profileId)}&status=1`);
        if (status.ready) break;
        if (status.job?.stage === "FAILED" || status.job?.stage === "CANCELLED") throw new Error(status.job.error ?? t(`imports.stage.${status.job.stage}`));
        await new Promise((r) => setTimeout(r, 3000));
      }
      window.location.href = `${paths.watchFile(bookId, profileId)}&download=1`;
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setPreparing(false);
    }
  };

  if (!profiles.data?.length) return null;
  return (
    <>
      <Button variant="outlined" size="large" startIcon={preparing ? <CircularProgress size={16} /> : <WatchIcon />} disabled={preparing} onClick={(e) => setAnchor(e.currentTarget)}>
        {preparing ? t("book.watchPreparing") : t("book.watchCopy")}
      </Button>
      <Menu open={!!anchor} anchorEl={anchor} onClose={() => setAnchor(null)}>
        {profiles.data.map((p) => (
          <MenuItem key={p.id} onClick={() => void fetchCopy(p.id)}>{p.name}</MenuItem>
        ))}
      </Menu>
      <Snackbar open={!!error} autoHideDuration={8000} onClose={() => setError(null)} anchorOrigin={{ vertical: "bottom", horizontal: "center" }}>
        <Alert severity="error" variant="filled" onClose={() => setError(null)}>{error}</Alert>
      </Snackbar>
    </>
  );
}

function EditDialog({ book, onClose, onSaved }: { book: BookDto; onClose: () => void; onSaved: (b: BookDto) => void }) {
  const { t } = useTranslation();
  const status = useStatus();
  const [form, setForm] = useState<MetadataDto>({ ...book.metadata });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [searching, setSearching] = useState(false);
  /** A cover found on a site, written into the book after its details are saved. */
  const [newCover, setNewCover] = useState<string | null>(null);
  const field = (key: keyof MetadataDto, label: string, extra?: object) => (
    <TextField label={label} value={form[key] ?? ""} onChange={(e) => setForm({ ...form, [key]: e.target.value || null })} fullWidth {...extra} />
  );
  const save = async () => {
    setBusy(true);
    try {
      let saved = await api.patch<BookDto>(paths.book(book.id), { metadata: { ...form, title: form.title.trim() }, baseRevision: book.stamp.revision });
      if (newCover) {
        const image = await fetch(paths.metadataCover(newCover), { credentials: "same-origin" });
        if (!image.ok) throw new Error(t("common.error"));
        saved = await request<BookDto>("PUT", `${paths.book(book.id)}/cover`, await image.blob(), { headers: { "Content-Type": "application/octet-stream" } });
      }
      onSaved(saved);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t("book.edit")}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          {status.data?.features.includes("metadata") && (
            <Button startIcon={<TravelExploreRoundedIcon />} onClick={() => setSearching(true)} sx={{ alignSelf: "flex-start" }}>
              {t("book.findOnline")}
            </Button>
          )}
          {newCover && (
            <Stack direction="row" spacing={1} alignItems="center">
              <Box component="img" src={paths.metadataCover(newCover)} alt="" sx={{ width: 48, height: 48, objectFit: "cover", borderRadius: 1 }} />
              <Typography variant="body2" sx={{ flex: 1 }}>{t("metadata.cover")}</Typography>
              <IconButton size="small" onClick={() => setNewCover(null)} aria-label={t("common.cancel")}><CloseRoundedIcon fontSize="small" /></IconButton>
            </Stack>
          )}
          {field("title", t("book.title"), { required: true })}
          {field("author", t("book.author"))}
          {field("narrator", t("book.narratorField"))}
          <Stack direction="row" spacing={2}>
            {field("series", t("book.series"))}
            <Box sx={{ width: 120 }}>{field("seriesIndex", t("book.seriesIndex"))}</Box>
          </Stack>
          <Stack direction="row" spacing={2}>
            {field("year", t("book.year"))}
            {field("genre", t("book.genre"))}
          </Stack>
          {field("description", t("book.description"), { multiline: true, minRows: 3 })}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t("common.cancel")}</Button>
        <Button variant="contained" onClick={save} disabled={busy || !form.title.trim()}>{t("common.save")}</Button>
      </DialogActions>
      {searching && (
        <MetadataSearchDialog
          facts={{ title: book.metadata.title, author: book.metadata.author, narrator: book.metadata.narrator, durationMs: book.durationMs }}
          current={form}
          onApply={(changes, cover) => {
            setForm({ ...form, ...changes });
            if (cover) setNewCover(cover);
            setSearching(false);
          }}
          onClose={() => setSearching(false)}
        />
      )}
    </Dialog>
  );
}
