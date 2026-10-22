import CancelRoundedIcon from "@mui/icons-material/CancelRounded";
import CheckCircleRoundedIcon from "@mui/icons-material/CheckCircleRounded";
import ClearAllIcon from "@mui/icons-material/ClearAll";
import CloseIcon from "@mui/icons-material/Close";
import CloudUploadRoundedIcon from "@mui/icons-material/CloudUploadRounded";
import CreateNewFolderIcon from "@mui/icons-material/CreateNewFolder";
import DeleteIcon from "@mui/icons-material/Delete";
import ErrorRoundedIcon from "@mui/icons-material/ErrorRounded";
import RefreshIcon from "@mui/icons-material/Refresh";
import SyncRoundedIcon from "@mui/icons-material/SyncRounded";
import UploadFileIcon from "@mui/icons-material/UploadFile";
import {
  Alert, alpha, Box, Button, Card, CardContent, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, Divider, FormControlLabel, IconButton,
  LinearProgress, List, ListItem, ListItemText, Stack, Switch, TextField, Typography,
} from "@mui/material";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { api, paths, request } from "../api/client";
import { keys, useImports, useTorrents, useUploads } from "../api/hooks";
import type { ImportJobDto, MetadataDto, TorrentBookDto, TorrentDto, UploadDto } from "../api/types.gen";
import { uploadFile } from "../api/upload";
import { PageHeader } from "../components/Ui";
import { formatBytes } from "../library";

export default function ImportsPage({ torrents }: { torrents: boolean }) {
  const { t } = useTranslation();
  return (
    <Stack spacing={3}>
      <PageHeader title={t("nav.imports")} subtitle={t("imports.subtitle")} />
      <UploadCard />
      {torrents ? <TorrentsCard /> : <Alert severity="info">{t("imports.torrentsUnavailable")}</Alert>}
      <JobsCard />
    </Stack>
  );
}

// -------------------------------------------------------------------------------------------- uploads

const AUDIO = /\.(mp3|m4a|m4b|mp4|aac|jpe?g|png|webp)$/i;

function UploadCard() {
  const { t } = useTranslation();
  const client = useQueryClient();
  const files = useRef<HTMLInputElement>(null);
  const folder = useRef<HTMLInputElement>(null);
  const [progress, setProgress] = useState<{ name: string; sent: number; total: number } | null>(null);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);

  const send = async (list: FileList | null, fromFolder: boolean) => {
    const chosen = [...(list ?? [])].filter((f) => AUDIO.test(f.name));
    if (chosen.length === 0) return;
    // A folder's own name names the book; its sub-folders (CD1, CD2) stay as the files' folders.
    const root = fromFolder ? chosen[0].webkitRelativePath.split("/")[0] : chosen[0].name.replace(/\.[^.]+$/, "");
    const total = chosen.reduce((s, f) => s + f.size, 0);
    let done = 0;
    setMessage(null);
    try {
      const uploads: UploadDto[] = [];
      for (const file of chosen) {
        const dirs = fromFolder ? file.webkitRelativePath.split("/").slice(1, -1) : [];
        uploads.push(await uploadFile(file, { kind: "SOURCE", relativeDir: dirs }, (sent) => setProgress({ name: file.name, sent: done + sent, total })));
        done += file.size;
      }
      await api.post<ImportJobDto>(paths.imports, { uploadIds: uploads.map((u) => u.id), sourceName: root });
      setMessage({ ok: true, text: t("imports.uploadStarted") });
      void client.invalidateQueries({ queryKey: keys.imports });
    } catch (e) {
      setMessage({ ok: false, text: e instanceof Error ? e.message : String(e) });
    } finally {
      setProgress(null);
      if (files.current) files.current.value = "";
      if (folder.current) folder.current.value = "";
    }
  };

  return (
    <Card variant="outlined">
      <CardContent>
        <Stack spacing={1.5}>
          <Typography variant="h6">{t("imports.upload")}</Typography>
          <Typography variant="body2" color="text.secondary">{t("imports.uploadHint")}</Typography>
          <Stack direction="row" sx={{ flexWrap: "wrap", gap: 1 }}>
            <Button variant="contained" startIcon={<UploadFileIcon />} disabled={!!progress} onClick={() => files.current?.click()}>{t("imports.upload")}</Button>
            <Button variant="outlined" startIcon={<CreateNewFolderIcon />} disabled={!!progress} onClick={() => folder.current?.click()}>{t("imports.uploadFolder")}</Button>
          </Stack>
          <input ref={files} type="file" multiple hidden accept=".mp3,.m4a,.m4b,.mp4,.aac,image/*" onChange={(e) => void send(e.target.files, false)} />
          <input ref={folder} type="file" hidden {...{ webkitdirectory: "", directory: "" }} onChange={(e) => void send(e.target.files, true)} />
          {progress && (
            <Box>
              <Typography variant="body2">{t("imports.uploading", { name: progress.name })} {formatBytes(progress.sent)} / {formatBytes(progress.total)}</Typography>
              <LinearProgress variant="determinate" value={(progress.sent / progress.total) * 100} />
            </Box>
          )}
          {message && <Alert severity={message.ok ? "success" : "error"} onClose={() => setMessage(null)}>{message.text}</Alert>}
        </Stack>
      </CardContent>
    </Card>
  );
}

// ------------------------------------------------------------------------------------------- torrents

function TorrentsCard() {
  const { t } = useTranslation();
  const list = useTorrents();
  const client = useQueryClient();
  const [link, setLink] = useState("");
  const [auto, setAuto] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [review, setReview] = useState<{ torrent: TorrentDto; book: TorrentBookDto } | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  const added = (torrent: TorrentDto) => {
    client.setQueryData<TorrentDto[]>(keys.torrents, (old) => [torrent, ...(old ?? []).filter((x) => x.id !== torrent.id)]);
    setLink("");
    setError(null);
  };
  const add = async () => {
    try {
      added(await api.post<TorrentDto>(paths.torrents, { link: link.trim(), autoImport: auto }));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };
  const addFile = async (file: File) => {
    try {
      added(await request<TorrentDto>("POST", `${paths.torrents}/file?autoImport=${auto}`, file, { headers: { "Content-Type": "application/x-bittorrent" } }));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      if (fileInput.current) fileInput.current.value = "";
    }
  };

  return (
    <Card variant="outlined">
      <CardContent>
        <Stack spacing={1.5}>
          <Typography variant="h6">{t("imports.torrents")}</Typography>
          <Stack direction={{ xs: "column", sm: "row" }} spacing={1} sx={{ alignItems: { sm: "center" } }}>
            <TextField size="small" fullWidth placeholder={t("imports.torrentLink")} value={link} onChange={(e) => setLink(e.target.value)} onKeyDown={(e) => e.key === "Enter" && link.trim() && void add()} />
            <Button variant="contained" onClick={() => void add()} disabled={!link.trim()} sx={{ whiteSpace: "nowrap", flexShrink: 0 }}>{t("imports.addTorrent")}</Button>
            <Button variant="outlined" startIcon={<UploadFileIcon />} onClick={() => fileInput.current?.click()} sx={{ whiteSpace: "nowrap", flexShrink: 0 }}>{t("imports.torrentFile")}</Button>
            <input ref={fileInput} type="file" hidden accept=".torrent,application/x-bittorrent" onChange={(e) => e.target.files?.[0] && void addFile(e.target.files[0])} />
          </Stack>
          <FormControlLabel control={<Switch checked={auto} onChange={(e) => setAuto(e.target.checked)} />} label={t("imports.autoImport")} sx={{ alignSelf: "flex-start", ml: -1 }} />
          {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}
          {(list.data?.length ?? 0) === 0 ? (
            <Typography color="text.secondary">{t("imports.noTorrents")}</Typography>
          ) : (
            <List disablePadding>
              {list.data!.map((torrent) => (
                <TorrentRow key={torrent.id} torrent={torrent} onReview={(book) => setReview({ torrent, book })} onError={setError} />
              ))}
            </List>
          )}
        </Stack>
      </CardContent>
      {review && <ReviewDialog torrent={review.torrent} book={review.book} onClose={() => setReview(null)} />}
    </Card>
  );
}

function TorrentRow({ torrent, onReview, onError }: { torrent: TorrentDto; onReview: (book: TorrentBookDto) => void; onError: (message: string) => void }) {
  const { t } = useTranslation();
  const client = useQueryClient();
  const navigate = useNavigate();
  const remove = async () => {
    try {
      await api.delete(`${paths.torrents}/${torrent.id}`);
      client.setQueryData<TorrentDto[]>(keys.torrents, (old) => old?.filter((x) => x.id !== torrent.id));
    } catch (e) {
      onError(e instanceof Error ? e.message : String(e));
    }
  };
  const downloading = torrent.phase === "DOWNLOADING";
  return (
    <ListItem disableGutters sx={{ display: "block", borderBottom: 1, borderColor: "divider", py: 1.5 }}>
      <Stack direction="row" sx={{ alignItems: "center" }} spacing={1}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography noWrap sx={{ fontWeight: 600 }}>{torrent.name}</Typography>
          <Typography variant="body2" color="text.secondary">
            {t(`imports.phase.${torrent.phase}`)}
            {downloading && ` · ${Math.round(torrent.progress * 100)}% · ${formatBytes(torrent.downloadedBytes)} / ${formatBytes(torrent.sizeBytes)} · ${formatBytes(torrent.downloadRate)}/s · ${t("imports.peers", { count: torrent.peers })}`}
          </Typography>
          {torrent.error && <Typography variant="body2" color="error">{torrent.error}</Typography>}
        </Box>
        {torrent.phase === "FAILED" && <IconButton onClick={() => api.post(`${paths.torrents}/${torrent.id}/retry`).catch((e: Error) => onError(e.message))} aria-label={t("common.retry")}><RefreshIcon /></IconButton>}
        <IconButton onClick={() => void remove()} aria-label={t("imports.remove")}><DeleteIcon /></IconButton>
      </Stack>
      {downloading && <LinearProgress variant="determinate" value={torrent.progress * 100} sx={{ my: 1 }} />}
      {torrent.books.map((book) => (
        <Stack key={book.id} direction="row" spacing={1} sx={{ alignItems: "center", pl: 2, mt: 0.5 }}>
          <Typography variant="body2" sx={{ flex: 1, minWidth: 0 }} noWrap>
            {book.metadata.seriesIndex && book.volume ? `${book.metadata.seriesIndex}. ` : ""}{book.metadata.title}
            <Typography component="span" variant="body2" color="text.secondary"> · {t(`imports.phase.${book.phase}`)}{book.phase === "CONVERTING" ? ` ${Math.round(book.progress * 100)}%` : ""}</Typography>
            {book.error && <Typography component="span" variant="body2" color="error"> · {book.error}</Typography>}
          </Typography>
          {book.phase === "COMPLETED" ? (
            <Button size="small" onClick={() => navigate(`/book/${book.bookId}`)}>{t("nav.library")}</Button>
          ) : book.phase !== "CONVERTING" && (
            <Button size="small" variant={book.phase === "AWAITING_REVIEW" ? "contained" : "text"} onClick={() => onReview(book)}>{t("imports.review")}</Button>
          )}
        </Stack>
      ))}
    </ListItem>
  );
}

function ReviewDialog({ torrent, book, onClose }: { torrent: TorrentDto; book: TorrentBookDto; onClose: () => void }) {
  const { t } = useTranslation();
  const client = useQueryClient();
  const [meta, setMeta] = useState<MetadataDto>({ ...book.metadata });
  const [kept, setKept] = useState<Set<number>>(new Set(book.files.filter((f) => f.wanted).map((f) => f.index)));
  const [error, setError] = useState<string | null>(null);
  const cover = useRef<HTMLInputElement>(null);
  const field = (key: keyof MetadataDto, label: string) => (
    <TextField label={label} value={meta[key] ?? ""} onChange={(e) => setMeta({ ...meta, [key]: e.target.value || null })} fullWidth size="small" />
  );
  const save = async () => {
    try {
      const updated = await api.put<TorrentDto>(`${paths.torrents}/${torrent.id}/books/${book.id}`, {
        metadata: meta,
        files: book.files.filter((f) => kept.has(f.index)).map((f) => f.index),
        autoImport: true,
      });
      client.setQueryData<TorrentDto[]>(keys.torrents, (old) => old?.map((x) => (x.id === updated.id ? updated : x)));
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle sx={{ display: "flex", alignItems: "center" }}>
        <Box sx={{ flex: 1 }}>{t("imports.reviewTitle")}</Box>
        <IconButton onClick={onClose}><CloseIcon /></IconButton>
      </DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          {field("title", t("book.title"))}
          {field("author", t("book.author"))}
          {field("narrator", t("book.narratorField"))}
          <Stack direction="row" spacing={1}>
            {field("series", t("book.series"))}
            <Box sx={{ width: 110 }}>{field("seriesIndex", t("book.seriesIndex"))}</Box>
          </Stack>
          {field("year", t("book.year"))}
          <Button startIcon={<UploadFileIcon />} onClick={() => cover.current?.click()} sx={{ alignSelf: "flex-start" }}>
            {t("book.cover")}{book.hasCustomCover ? " ✓" : ""}
          </Button>
          <input ref={cover} type="file" accept="image/*" hidden onChange={async (e) => {
            const file = e.target.files?.[0];
            if (!file) return;
            try {
              await request("PUT", `${paths.torrents}/${torrent.id}/books/${book.id}/cover`, file, { headers: { "Content-Type": "application/octet-stream" } });
            } catch (err) {
              setError(err instanceof Error ? err.message : String(err));
            }
          }} />
          <Typography variant="subtitle2">{t("imports.files")}</Typography>
          <List dense disablePadding sx={{ maxHeight: 280, overflow: "auto" }}>
            {book.files.map((f) => (
              <ListItem key={f.index} disableGutters secondaryAction={<Typography variant="caption" color="text.secondary">{formatBytes(f.sizeBytes)}</Typography>}>
                <Checkbox size="small" checked={kept.has(f.index)} onChange={(e) => {
                  const next = new Set(kept);
                  if (e.target.checked) next.add(f.index); else next.delete(f.index);
                  setKept(next);
                }} />
                <ListItemText primary={f.path.split("/").pop()} secondary={f.path.includes("/") ? f.path.split("/").slice(0, -1).join("/") : undefined} />
              </ListItem>
            ))}
          </List>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t("common.cancel")}</Button>
        <Button variant="contained" onClick={() => void save()} disabled={!meta.title?.trim() || kept.size === 0}>{t("imports.convert")}</Button>
      </DialogActions>
    </Dialog>
  );
}

// ---------------------------------------------------------------------------------------------- jobs

const ACTIVE_STAGES = ["QUEUED", "PREPARING", "CONVERTING", "WRITING", "VERIFYING", "FINISHING"];

function JobsCard() {
  const { t } = useTranslation();
  const jobs = useImports();
  const uploads = useUploads();
  const client = useQueryClient();
  const navigate = useNavigate();
  const [clearing, setClearing] = useState(false);
  // A job that finished since the last poll brings a new book: refresh the library.
  const done = jobs.data?.filter((j) => j.stage === "DONE").length ?? 0;
  useEffect(() => {
    if (done > 0) void client.invalidateQueries({ queryKey: keys.books });
  }, [done, client]);

  const remove = async (ids: string[]) => {
    await Promise.allSettled(ids.map((id) => api.delete(`${paths.imports}/${id}`)));
    client.setQueryData<ImportJobDto[]>(keys.imports, (old) => old?.filter((j) => !ids.includes(j.id)));
  };
  // A stuck upload from a phone: the phone's own attempt then fails and can be started again.
  const cancelUpload = async (id: string) => {
    await api.delete(paths.upload(id)).catch(() => undefined);
    client.setQueryData<UploadDto[]>(keys.uploads, (old) => old?.filter((u) => u.id !== id));
  };
  const finished = jobs.data?.filter((j) => !ACTIVE_STAGES.includes(j.stage)).map((j) => j.id) ?? [];
  const clearFinished = async () => {
    setClearing(true);
    try {
      await remove(finished);
    } finally {
      setClearing(false);
    }
  };
  const empty = (jobs.data?.length ?? 0) === 0 && (uploads.data?.length ?? 0) === 0;

  return (
    <Card variant="outlined">
      <CardContent>
        <Stack direction="row" sx={{ alignItems: "center", justifyContent: "space-between", mb: 1, minHeight: 36 }}>
          <Typography variant="h6">{t("imports.jobs")}</Typography>
          {finished.length > 0 && (
            <Button size="small" startIcon={<ClearAllIcon />} disabled={clearing} onClick={() => void clearFinished()}>{t("imports.clearFinished")}</Button>
          )}
        </Stack>
        {empty ? (
          <Typography color="text.secondary">{t("imports.noJobs")}</Typography>
        ) : (
          <Stack divider={<Divider flexItem />}>
            {uploads.data?.map((u) => (
              <JobLine
                key={u.id}
                icon={<CloudUploadRoundedIcon />}
                tone="primary"
                title={u.fileName}
                subtitle={`${t("imports.incoming", { from: u.from ?? "?" })} · ${formatBytes(u.receivedBytes)} / ${formatBytes(u.sizeBytes)}`}
                progress={u.sizeBytes ? u.receivedBytes / u.sizeBytes : 0}
                action={<IconButton size="small" aria-label={t("imports.cancel")} onClick={() => void cancelUpload(u.id)}><CloseIcon fontSize="small" /></IconButton>}
              />
            ))}
            {jobs.data?.map((job) => {
              const active = ACTIVE_STAGES.includes(job.stage);
              const openable = job.stage === "DONE" && !!job.bookId;
              return (
                <JobLine
                  key={job.id}
                  icon={active ? <SyncRoundedIcon /> : job.stage === "DONE" ? <CheckCircleRoundedIcon /> : job.stage === "FAILED" ? <ErrorRoundedIcon /> : <CancelRoundedIcon />}
                  tone={job.stage === "DONE" ? "secondary" : job.stage === "FAILED" ? "error" : active ? "primary" : undefined}
                  title={job.title}
                  onTitle={openable ? () => navigate(`/book/${job.bookId}`) : undefined}
                  subtitle={`${t(`imports.stage.${job.stage}`)}${job.progress != null && active ? ` ${Math.round(job.progress * 100)}%` : ""}`}
                  error={job.error}
                  progress={active ? job.progress ?? null : undefined}
                  action={active ? (
                    <Button size="small" onClick={() => void api.post(`${paths.imports}/${job.id}/cancel`)}>{t("imports.cancel")}</Button>
                  ) : (
                    <IconButton size="small" aria-label={t("imports.remove")} onClick={() => void remove([job.id])}><CloseIcon fontSize="small" /></IconButton>
                  )}
                />
              );
            })}
          </Stack>
        )}
      </CardContent>
    </Card>
  );
}

/** One upload or conversion: a status icon, what it is, how far along, and what can be done with it. */
function JobLine({ icon, tone, title, onTitle, subtitle, error, progress, action }: {
  icon: ReactNode;
  tone?: "primary" | "secondary" | "error";
  title: string;
  onTitle?: () => void;
  subtitle: string;
  error?: string | null;
  /** A fraction, null while unknown (an indeterminate bar), undefined for no bar. */
  progress?: number | null;
  action?: ReactNode;
}) {
  return (
    <Stack direction="row" spacing={1.5} sx={{ alignItems: "center", py: 1.25 }}>
      <Box
        sx={(theme) => ({
          width: 36, height: 36, borderRadius: 2.5, flexShrink: 0, display: "grid", placeItems: "center",
          color: tone ? theme.palette[tone].main : theme.palette.text.secondary,
          bgcolor: alpha(tone ? theme.palette[tone].main : theme.palette.text.secondary, 0.12),
          "& svg": { fontSize: 20 },
        })}
      >
        {icon}
      </Box>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography
          noWrap
          onClick={onTitle}
          sx={{ fontWeight: 700, cursor: onTitle ? "pointer" : undefined, "&:hover": onTitle ? { color: "primary.main" } : undefined }}
        >
          {title}
        </Typography>
        <Typography variant="body2" color="text.secondary" noWrap>{subtitle}</Typography>
        {error && <Typography variant="body2" color="error" sx={{ wordBreak: "break-word" }}>{error}</Typography>}
        {progress !== undefined && (
          <LinearProgress variant={progress === null ? "indeterminate" : "determinate"} value={(progress ?? 0) * 100} sx={{ mt: 0.75, height: 4 }} />
        )}
      </Box>
      {action && <Box sx={{ flexShrink: 0 }}>{action}</Box>}
    </Stack>
  );
}
