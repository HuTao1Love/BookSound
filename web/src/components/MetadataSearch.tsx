import CheckRoundedIcon from "@mui/icons-material/CheckRounded";
import CloseRoundedIcon from "@mui/icons-material/CloseRounded";
import OpenInNewRoundedIcon from "@mui/icons-material/OpenInNewRounded";
import SearchRoundedIcon from "@mui/icons-material/SearchRounded";
import {
  Alert, Box, Button, Card, CardActionArea, Checkbox, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle,
  FormControlLabel, IconButton, InputAdornment, Link, Stack, TextField, Typography,
} from "@mui/material";
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { api, paths } from "../api/client";
import type { MatchState, MatchVerdict, MetadataDto, MetadataMatchDto, MetadataSearchRequest, MetadataSearchResponse } from "../api/types.gen";
import { formatDuration } from "../library";

/** The fields a site's details may fill, in the edit form's order. */
const FIELDS: (keyof MetadataDto)[] = ["title", "author", "narrator", "series", "seriesIndex", "year", "genre", "description"];

const verdictColor: Record<MatchVerdict, "success" | "info" | "warning" | "default"> = {
  SAME_RECORDING: "success",
  SAME_BOOK: "info",
  DOUBTFUL: "warning",
  DIFFERENT: "default",
};

/**
 * Looks the book up on audiobook sites (through the server) and fills chosen details into the edit form.
 * Each result says whether it is this book: the same recording, the same book in another recording, or
 * doubtful; picking one shows what would change, with empty fields ticked.
 */
export function MetadataSearchDialog({
  facts, current, onApply, onClose,
}: {
  facts: MetadataSearchRequest;
  current: MetadataDto;
  onApply: (changes: Partial<MetadataDto>, coverUrl: string | null) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  // What was known when the dialog opened: the form changing underneath doesn't search again.
  const [known] = useState(facts);
  const [query, setQuery] = useState([facts.title, facts.author].filter(Boolean).join(" "));
  const [result, setResult] = useState<MetadataSearchResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [chosen, setChosen] = useState<MetadataMatchDto | null>(null);

  const search = useCallback(async (text?: string) => {
    setBusy(true);
    setError(null);
    try {
      setResult(await api.post<MetadataSearchResponse>(paths.metadataSearch, { ...known, query: text }));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [known]);

  // The first search uses everything known of the book; the query box is for when that finds nothing.
  useEffect(() => { void search(); }, [search]);

  if (chosen) {
    return <ApplyDialog match={chosen} current={current} onBack={() => setChosen(null)} onApply={onApply} />;
  }
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t("metadata.title")}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <TextField
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") void search(query); }}
            label={t("metadata.query")}
            fullWidth
            slotProps={{
              input: {
                endAdornment: (
                  <InputAdornment position="end">
                    <IconButton onClick={() => void search(query)} disabled={busy || !query.trim()} aria-label={t("metadata.search")}>
                      <SearchRoundedIcon />
                    </IconButton>
                  </InputAdornment>
                ),
              },
            }}
          />
          {error && <Alert severity="error">{error}</Alert>}
          {result && result.failedSources && result.failedSources.length > 0 && (
            <Alert severity="warning">{t("metadata.failed", { sources: result.failedSources.join(", ") })}</Alert>
          )}
          {busy && <CircularProgress sx={{ alignSelf: "center" }} />}
          {!busy && result && result.matches.length === 0 && <Typography color="text.secondary">{t("metadata.nothing")}</Typography>}
          {!busy && result?.matches.map((match) => <MatchCard key={match.candidate.url} match={match} onChoose={() => setChosen(match)} />)}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t("common.close")}</Button>
      </DialogActions>
    </Dialog>
  );
}

function MatchCard({ match, onChoose }: { match: MetadataMatchDto; onChoose: () => void }) {
  const { t } = useTranslation();
  const { candidate } = match;
  const meta = candidate.metadata;
  return (
    <Card variant="outlined">
      <CardActionArea onClick={onChoose} sx={{ p: 1.5 }}>
        <Stack direction="row" spacing={1.5}>
          {candidate.coverUrl && (
            <Box component="img" src={paths.metadataCover(candidate.coverUrl)} alt="" loading="lazy"
              sx={{ width: 64, height: 64, objectFit: "cover", borderRadius: 1, flexShrink: 0 }} />
          )}
          <Box sx={{ minWidth: 0, flex: 1 }}>
            <Typography variant="subtitle1" noWrap>{meta.title}</Typography>
            <Typography variant="body2" color="text.secondary" noWrap>
              {[meta.author, meta.narrator && t("book.narrator", { name: meta.narrator }), candidate.durationMs && formatDuration(candidate.durationMs, false)]
                .filter(Boolean).join(" · ")}
            </Typography>
            <Stack direction="row" spacing={0.5} sx={{ mt: 0.5, flexWrap: "wrap", rowGap: 0.5 }}>
              <Chip size="small" color={verdictColor[match.verdict]} label={t(`metadata.verdict.${match.verdict}`)} />
              <StateChip label={t("book.narratorField")} state={match.narrator} />
              <StateChip label={t("metadata.length")} state={match.duration} />
              <Chip size="small" variant="outlined" label={candidate.sourceName} />
            </Stack>
          </Box>
        </Stack>
      </CardActionArea>
    </Card>
  );
}

/** What agreed with the book (✓) or not (✗); nothing when it couldn't be compared. */
function StateChip({ label, state }: { label: string; state: MatchState }) {
  if (state === "UNKNOWN") return null;
  const match = state === "MATCH";
  return (
    <Chip size="small" variant="outlined" color={match ? "success" : "warning"} label={label}
      icon={match ? <CheckRoundedIcon /> : <CloseRoundedIcon />} />
  );
}

function ApplyDialog({
  match, current, onBack, onApply,
}: {
  match: MetadataMatchDto;
  current: MetadataDto;
  onBack: () => void;
  onApply: (changes: Partial<MetadataDto>, coverUrl: string | null) => void;
}) {
  const { t } = useTranslation();
  const found = match.candidate.metadata;
  const differing = FIELDS.filter((key) => {
    const value = found[key];
    return value != null && value !== "" && value !== (current[key] ?? "");
  });
  // Empty fields are filled; a narrator from another recording is not this one's.
  const [picked, setPicked] = useState<Set<keyof MetadataDto>>(() => new Set(differing.filter((key) =>
    !current[key] && !(key === "narrator" && match.narrator === "CONFLICT"))));
  const [cover, setCover] = useState(false);
  const toggle = (key: keyof MetadataDto) => setPicked((old) => {
    const next = new Set(old);
    if (next.has(key)) next.delete(key); else next.add(key);
    return next;
  });
  const apply = () => {
    const changes: Partial<MetadataDto> = {};
    for (const key of picked) (changes as Record<string, unknown>)[key] = found[key];
    onApply(changes, cover ? match.candidate.coverUrl ?? null : null);
  };
  return (
    <Dialog open onClose={onBack} fullWidth maxWidth="sm">
      <DialogTitle>{t("metadata.applyTitle")}</DialogTitle>
      <DialogContent>
        <Stack spacing={1}>
          <Link href={match.candidate.url} target="_blank" rel="noopener noreferrer" variant="body2" sx={{ display: "inline-flex", alignItems: "center", gap: 0.5 }}>
            {match.candidate.sourceName} <OpenInNewRoundedIcon fontSize="inherit" />
          </Link>
          {differing.length === 0 && !match.candidate.coverUrl && <Typography color="text.secondary">{t("metadata.sameAlready")}</Typography>}
          {differing.map((key) => (
            <FormControlLabel
              key={key}
              control={<Checkbox checked={picked.has(key)} onChange={() => toggle(key)} />}
              sx={{ alignItems: "flex-start", "& .MuiCheckbox-root": { pt: 0.5 } }}
              label={
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="caption" color="text.secondary">{t(`book.${key === "narrator" ? "narratorField" : key}`)}</Typography>
                  <Typography variant="body2" sx={{ whiteSpace: "pre-line", display: "-webkit-box", WebkitLineClamp: 4, WebkitBoxOrient: "vertical", overflow: "hidden" }}>
                    {found[key]}
                  </Typography>
                  {current[key] && <Typography variant="caption" color="text.secondary" sx={{ textDecoration: "line-through" }} noWrap component="div">{current[key]}</Typography>}
                </Box>
              }
            />
          ))}
          {match.candidate.coverUrl && (
            <FormControlLabel
              control={<Checkbox checked={cover} onChange={(e) => setCover(e.target.checked)} />}
              label={
                <Stack direction="row" spacing={1} alignItems="center">
                  <Box component="img" src={paths.metadataCover(match.candidate.coverUrl)} alt="" sx={{ width: 56, height: 56, objectFit: "cover", borderRadius: 1 }} />
                  <Typography variant="body2">{t("metadata.cover")}</Typography>
                </Stack>
              }
            />
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onBack}>{t("metadata.back")}</Button>
        <Button variant="contained" onClick={apply} disabled={picked.size === 0 && !cover}>{t("metadata.apply")}</Button>
      </DialogActions>
    </Dialog>
  );
}
