import DeleteIcon from "@mui/icons-material/Delete";
import DownloadIcon from "@mui/icons-material/Download";
import EditIcon from "@mui/icons-material/Edit";
import ImageIcon from "@mui/icons-material/Image";
import PlayArrowIcon from "@mui/icons-material/PlayArrow";
import ReplayIcon from "@mui/icons-material/Replay";
import WatchIcon from "@mui/icons-material/Watch";
import {
  Alert, Box, Button, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, Grid, List, ListItemButton, ListItemText,
  Menu, MenuItem, Stack, TextField, Typography,
} from "@mui/material";
import { useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate, useParams } from "react-router-dom";
import { api, paths, request } from "../api/client";
import { keys, useBook, useMe, useWatchProfiles } from "../api/hooks";
import type { BookDto, MetadataDto, WatchFileStatusDto } from "../api/types.gen";
import BookCover from "../components/BookCover";
import { chapterAt, formatDuration, isStarted } from "../library";
import { usePlayer } from "../player/PlayerContext";

export default function BookPage() {
  const { t } = useTranslation();
  const { id = "" } = useParams();
  const entry = useBook(id);
  const me = useMe();
  const player = usePlayer();
  const client = useQueryClient();
  const navigate = useNavigate();
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
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

  return (
    <Stack spacing={3}>
      {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}
      <Grid container spacing={3}>
        <Grid size={{ xs: 12, sm: 4 }}>
          <Box sx={{ maxWidth: 320, mx: "auto" }}>
            <BookCover book={book} size={640} radius={2} />
          </Box>
        </Grid>
        <Grid size={{ xs: 12, sm: 8 }}>
          <Stack spacing={1.5}>
            <Typography variant="h4" sx={{ fontWeight: 600, fontSize: { xs: 26, md: 34 } }}>{meta.title}</Typography>
            {meta.author && <Typography variant="h6" color="text.secondary">{meta.author}</Typography>}
            {meta.series && (
              <Typography sx={{ cursor: "pointer" }} color="primary" onClick={() => navigate(`/series/${encodeURIComponent(meta.series!)}`)}>
                {meta.series}{meta.seriesIndex ? ` #${meta.seriesIndex}` : ""}
              </Typography>
            )}
            {meta.narrator && <Typography color="text.secondary">{t("book.narrator", { name: meta.narrator })}</Typography>}
            <Typography color="text.secondary">
              {formatDuration(book.durationMs, false)}
              {meta.year ? ` · ${meta.year}` : ""}
              {playback?.finished ? ` · ${t("book.finished")}` : isStarted(playback) ? ` · ${t("book.left", { time: formatDuration(book.durationMs - position, false) })}` : ""}
            </Typography>
            <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
              <Button variant="contained" size="large" startIcon={<PlayArrowIcon />} onClick={() => { player.play(entry.data!); navigate("/player"); }}>
                {isStarted(playback) ? t("book.resume") : t("book.play")}
              </Button>
              {isStarted(playback) && (
                <Button startIcon={<ReplayIcon />} onClick={() => { player.play(entry.data!, 0); navigate("/player"); }}>{t("book.restart")}</Button>
              )}
              <Button startIcon={<DownloadIcon />} href={`${paths.bookFile(id)}?download=1`}>{t("book.downloadFile")}</Button>
              <WatchCopyButton bookId={id} />
              <Button startIcon={<EditIcon />} onClick={() => setEditing(true)}>{t("book.edit")}</Button>
              <Button startIcon={<ImageIcon />} onClick={() => coverInput.current?.click()}>{t("book.cover")}</Button>
              {me.data?.user.isAdmin && <Button color="error" startIcon={<DeleteIcon />} onClick={() => setDeleting(true)}>{t("common.delete")}</Button>}
              <input ref={coverInput} type="file" accept="image/*" hidden onChange={(e) => e.target.files?.[0] && changeCover(e.target.files[0])} />
            </Stack>
            {meta.description && <Typography sx={{ whiteSpace: "pre-line" }}>{meta.description}</Typography>}
          </Stack>
        </Grid>
      </Grid>

      {(book.chapters?.length ?? 0) > 1 && (
        <Box>
          <Typography variant="h6" sx={{ mb: 1 }}>{t("book.chapters")}</Typography>
          <List dense>
            {book.chapters!.map((c, i) => (
              <ListItemButton key={c.index} selected={(isCurrent || isStarted(playback)) && i === currentChapter} onClick={() => player.play(entry.data!, c.startMs)}>
                <ListItemText primary={c.title} />
                <Typography variant="body2" color="text.secondary">{formatDuration(c.startMs)}</Typography>
              </ListItemButton>
            ))}
          </List>
        </Box>
      )}

      {editing && <EditDialog book={book} onClose={() => setEditing(false)} onSaved={(b) => { setEditing(false); refresh(b); }} />}
      <Dialog open={deleting} onClose={() => setDeleting(false)}>
        <DialogTitle>{t("book.deleteTitle")}</DialogTitle>
        <DialogContent>{t("book.deleteText")}</DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleting(false)}>{t("common.cancel")}</Button>
          <Button color="error" onClick={async () => { await api.delete(paths.book(id)); refresh(); navigate("/"); }}>{t("common.delete")}</Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

/** Builds the smaller watch copy on the server if needed, then downloads it. */
function WatchCopyButton({ bookId }: { bookId: string }) {
  const { t } = useTranslation();
  const profiles = useWatchProfiles();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const [preparing, setPreparing] = useState(false);

  const fetchCopy = async (profileId: string) => {
    setAnchor(null);
    setPreparing(true);
    try {
      for (;;) {
        const response = await fetch(`${paths.watchFile(bookId, profileId)}&status=1`, { credentials: "same-origin" });
        const status = (await response.json()) as WatchFileStatusDto;
        if (status.ready) break;
        if (status.job?.stage === "FAILED") throw new Error(status.job.error ?? "failed");
        await new Promise((r) => setTimeout(r, 3000));
      }
      window.location.href = `${paths.watchFile(bookId, profileId)}&download=1`;
    } finally {
      setPreparing(false);
    }
  };

  if (!profiles.data?.length) return null;
  return (
    <>
      <Button startIcon={preparing ? <CircularProgress size={16} /> : <WatchIcon />} disabled={preparing} onClick={(e) => setAnchor(e.currentTarget)}>
        {preparing ? t("book.watchPreparing") : t("book.watchCopy")}
      </Button>
      <Menu open={!!anchor} anchorEl={anchor} onClose={() => setAnchor(null)}>
        {profiles.data.map((p) => (
          <MenuItem key={p.id} onClick={() => void fetchCopy(p.id)}>{p.name}</MenuItem>
        ))}
      </Menu>
    </>
  );
}

function EditDialog({ book, onClose, onSaved }: { book: BookDto; onClose: () => void; onSaved: (b: BookDto) => void }) {
  const { t } = useTranslation();
  const [form, setForm] = useState<MetadataDto>({ ...book.metadata });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const field = (key: keyof MetadataDto, label: string, extra?: object) => (
    <TextField label={label} value={form[key] ?? ""} onChange={(e) => setForm({ ...form, [key]: e.target.value || null })} fullWidth {...extra} />
  );
  const save = async () => {
    setBusy(true);
    try {
      onSaved(await api.patch<BookDto>(paths.book(book.id), { metadata: { ...form, title: form.title.trim() }, baseRevision: book.stamp.revision }));
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
    </Dialog>
  );
}
