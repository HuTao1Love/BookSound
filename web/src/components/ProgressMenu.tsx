import MoreHorizRoundedIcon from "@mui/icons-material/MoreHorizRounded";
import RestartAltRoundedIcon from "@mui/icons-material/RestartAltRounded";
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, IconButton, ListItemIcon, Menu, MenuItem, Tooltip } from "@mui/material";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import type { LibraryEntryDto } from "../api/types.gen";
import { usePlayer } from "../player/PlayerContext";

export default function ProgressMenu({ entry }: { entry: LibraryEntryDto }) {
  const { t } = useTranslation();
  const player = usePlayer();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const [confirm, setConfirm] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState(false);
  const started = (entry.playback?.positionMs ?? 0) > 0 || entry.playback?.finished || (player.book?.id === entry.book.id && player.positionMs > 0);
  if (!started && !confirm) return null;
  return <>
    <Tooltip title={t("common.more")}><IconButton size="small" aria-label={`${t("common.more")} · ${entry.book.metadata.title}`} onClick={(event) => { event.stopPropagation(); setAnchor(event.currentTarget); }}><MoreHorizRoundedIcon fontSize="small" /></IconButton></Tooltip>
    <Menu anchorEl={anchor} open={!!anchor} onClose={() => setAnchor(null)}>
      <MenuItem onClick={() => { setAnchor(null); setError(false); setConfirm(true); }}><ListItemIcon><RestartAltRoundedIcon fontSize="small" /></ListItemIcon>{t("book.resetProgress")}</MenuItem>
    </Menu>
    <Dialog open={confirm} onClose={() => { if (!pending) setConfirm(false); }}>
      <DialogTitle>{t("book.resetTitle")}</DialogTitle>
      <DialogContent><DialogContentText>{entry.book.metadata.title}</DialogContentText><DialogContentText sx={{ mt: 1 }}>{t("book.resetHint")}</DialogContentText>{error && <Alert severity="error" sx={{ mt: 2 }}>{t("common.error")}</Alert>}</DialogContent>
      <DialogActions><Button disabled={pending} onClick={() => setConfirm(false)}>{t("common.cancel")}</Button><Button disabled={pending} onClick={async () => {
        setPending(true);
        setError(false);
        try { await player.resetProgress(entry); setConfirm(false); } catch { setError(true); } finally { setPending(false); }
      }}>{t("book.resetProgress")}</Button></DialogActions>
    </Dialog>
  </>;
}
