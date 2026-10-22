import LibraryBooksRoundedIcon from "@mui/icons-material/LibraryBooksRounded";
import SearchRoundedIcon from "@mui/icons-material/SearchRounded";
import SwapVertRoundedIcon from "@mui/icons-material/SwapVertRounded";
import { Alert, Box, Chip, CircularProgress, IconButton, InputAdornment, MenuItem, Stack, TextField, Tooltip, Typography } from "@mui/material";
import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { useBooks } from "../api/hooks";
import type { LibraryEntryDto } from "../api/types.gen";
import BookCover from "../components/BookCover";
import { SeriesCard, Shelf } from "../components/SeriesParts";
import { EmptyState, PageHeader, PlayButton, ProgressLine, SectionTitle } from "../components/Ui";
import { applyQuery, defaultDescending, formatDuration, groupSeries, progressFilters, sortFields, type ProgressFilter, type SortField } from "../library";
import { usePlayer } from "../player/PlayerContext";

function stored<T extends string>(key: string, fallback: T): T {
  try {
    return (localStorage.getItem(key) as T | null) ?? fallback;
  } catch {
    return fallback;
  }
}

function remember(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // private mode
  }
}

export default function LibraryPage() {
  const { t } = useTranslation();
  const books = useBooks();
  const navigate = useNavigate();
  const [text, setText] = useState("");
  const [sort, setSort] = useState<SortField>(() => stored("library.sort", "RECENT"));
  const [descending, setDescending] = useState(() => stored("library.descending", "true") === "true");
  const [filter, setFilter] = useState<ProgressFilter>(() => stored("library.filter", "ALL"));

  // As on the phone: books outside any series on one shelf, then a card per series.
  const sections = useMemo(() => groupSeries(applyQuery(books.data ?? [], text, sort, descending, filter)), [books.data, text, sort, descending, filter]);

  // What one was listening to, latest first: the row on top.
  const listening = useMemo(
    () => (books.data ?? [])
      .filter((e) => e.playback && !e.playback.finished && e.playback.positionMs > 0)
      .sort((a, b) => (b.playback?.lastPlayedAt ?? 0) - (a.playback?.lastPlayedAt ?? 0))
      .slice(0, 8),
    [books.data],
  );

  if (books.isLoading) return <CircularProgress sx={{ display: "block", mx: "auto", mt: 8 }} />;
  if (books.isError) return <Alert severity="error">{t("common.error")}</Alert>;
  const all = books.data ?? [];
  const hours = Math.round(all.reduce((sum, e) => sum + e.book.durationMs, 0) / 3_600_000);

  return (
    <Box>
      <PageHeader
        title={t("nav.library")}
        subtitle={all.length > 0 ? `${t("library.count", { count: all.length })} · ${t("library.hours", { h: hours })}` : undefined}
      />

      {all.length === 0 ? (
        <EmptyState icon={<LibraryBooksRoundedIcon />} title={t("library.empty")} hint={t("library.emptyHint")} />
      ) : (
        <Stack spacing={4}>
          {listening.length > 0 && !text.trim() && (
            <Box>
              <SectionTitle>{t("library.continue")}</SectionTitle>
              <Box sx={{ display: "grid", gridAutoFlow: "column", gridAutoColumns: { xs: "86%", sm: "minmax(340px, 400px)" }, gap: 2, overflowX: "auto", pb: 1, mx: { xs: -2, sm: 0 }, px: { xs: 2, sm: 0 }, scrollSnapType: "x mandatory", "&::-webkit-scrollbar": { display: "none" } }}>
                {listening.map((e) => <ContinueCard key={e.book.id} entry={e} />)}
              </Box>
            </Box>
          )}

          <Box>
            <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5} sx={{ mb: 1.5, justifyContent: "space-between" }}>
              <TextField
                size="small"
                placeholder={t("library.search")}
                value={text}
                onChange={(e) => setText(e.target.value)}
                sx={{ flex: 1, maxWidth: { sm: 440 } }}
                slotProps={{ input: { startAdornment: <InputAdornment position="start"><SearchRoundedIcon /></InputAdornment> } }}
              />
              <Stack direction="row" spacing={0.5} sx={{ alignItems: "center", flexShrink: 0 }}>
                <TextField
                  select
                  size="small"
                  value={sort}
                  onChange={(e) => {
                    const field = e.target.value as SortField;
                    setSort(field);
                    setDescending(defaultDescending(field));
                    remember("library.sort", field);
                    remember("library.descending", String(defaultDescending(field)));
                  }}
                  sx={{ minWidth: 190, flex: { xs: 1, sm: "none" } }}
                  aria-label={t("library.sort")}
                >
                  {sortFields.map((f) => <MenuItem key={f} value={f}>{t(`library.sortBy.${f}`)}</MenuItem>)}
                </TextField>
                <Tooltip title={t("library.sort")}>
                  <IconButton onClick={() => { setDescending(!descending); remember("library.descending", String(!descending)); }} aria-label="order">
                    <SwapVertRoundedIcon sx={{ transform: descending ? undefined : "scaleY(-1)" }} />
                  </IconButton>
                </Tooltip>
              </Stack>
            </Stack>
              <Stack direction="row" spacing={1} sx={{ mb: 2.5, alignItems: "center", overflowX: "auto", "&::-webkit-scrollbar": { display: "none" } }}>
                {progressFilters.map((f) => (
                  <Chip
                    key={f}
                    label={t(`library.filters.${f}`)}
                    color={filter === f ? "primary" : "default"}
                    variant={filter === f ? "filled" : "outlined"}
                    onClick={() => { setFilter(f); remember("library.filter", f); }}
                    sx={{ flexShrink: 0 }}
                  />
                ))}
              </Stack>
            {sections.length === 0 ? (
              <EmptyState icon={<SearchRoundedIcon />} title={t("library.nothingFound")} />
            ) : (
              // Series cards, two side by side on wide screens.
              <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fill, minmax(min(100%, 520px), 1fr))" }}>
                {sections.map((section) => section.series === null ? (
                  <Box key="standalone" sx={{ gridColumn: "1 / -1", minWidth: 0, mx: { xs: -2, sm: 0 } }}>
                    <Shelf books={section.books} numbered={false} onOpen={(e) => navigate(`/book/${e.book.id}`)} inset={{ xs: 2, sm: 0 }} />
                  </Box>
                ) : (
                  <SeriesCard
                    key={`series:${section.series}`}
                    series={section.series}
                    books={section.books}
                    onOpenSeries={() => navigate(`/series/${encodeURIComponent(section.series!)}`)}
                    onOpenBook={(e) => navigate(`/book/${e.book.id}`)}
                  />
                ))}
              </Box>
            )}
          </Box>
        </Stack>
      )}
    </Box>
  );
}

function ContinueCard({ entry }: { entry: LibraryEntryDto }) {
  const { t } = useTranslation();
  const player = usePlayer();
  const navigate = useNavigate();
  const { book, playback } = entry;
  const isCurrent = player.book?.id === book.id;
  const position = isCurrent ? player.positionMs : playback?.positionMs ?? 0;
  return (
    <Box
      onClick={() => navigate(`/book/${book.id}`)}
      sx={{ scrollSnapAlign: "start", display: "flex", gap: 2, alignItems: "center", p: 1.5, borderRadius: 4, cursor: "pointer", bgcolor: "surface.main", border: 1, borderColor: "divider", transition: "background-color 160ms", "&:hover": { bgcolor: "surface.high" } }}
    >
      <Box sx={{ width: 84, flexShrink: 0 }}>
        <BookCover book={book} size={256} radius={2} />
      </Box>
      <Box sx={{ minWidth: 0, flex: 1 }}>
        <Typography sx={{ fontWeight: 800, lineHeight: 1.25, display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden" }}>{book.metadata.title}</Typography>
        <Typography variant="caption" color="text.secondary" noWrap component="div">{book.metadata.author}</Typography>
        <ProgressLine value={book.durationMs ? position / book.durationMs : 0} sx={{ mt: 1.25 }} />
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5 }}>
          {t("book.left", { time: formatDuration(Math.max(0, book.durationMs - position), false) })}
        </Typography>
      </Box>
      <PlayButton
        size={46}
        playing={isCurrent && player.playing}
        onClick={(e) => { e.stopPropagation(); if (isCurrent) player.toggle(); else player.play(entry); }}
      />
    </Box>
  );
}
