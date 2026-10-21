import SearchIcon from "@mui/icons-material/Search";
import SwapVertIcon from "@mui/icons-material/SwapVert";
import { Alert, Box, Card, CardActionArea, CircularProgress, IconButton, InputAdornment, MenuItem, Stack, TextField, Typography } from "@mui/material";
import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { useBooks } from "../api/hooks";
import BookCover from "../components/BookCover";
import { applyQuery, defaultDescending, groupSeries, progressFilters, progressOf, sortFields, type LibraryItem, type ProgressFilter, type SortField } from "../library";

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

  const items: LibraryItem[] = useMemo(() => {
    const sorted = applyQuery(books.data ?? [], text, sort, descending, filter);
    // While searching every match is shown on its own; otherwise series collapse.
    return text.trim() ? sorted.map((entry) => ({ kind: "book" as const, entry })) : groupSeries(sorted);
  }, [books.data, text, sort, descending, filter]);

  if (books.isLoading) return <CircularProgress sx={{ display: "block", mx: "auto", mt: 8 }} />;
  if (books.isError) return <Alert severity="error">{t("common.error")}</Alert>;

  return (
    <Stack spacing={2}>
      <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5}>
        <TextField
          fullWidth
          size="small"
          placeholder={t("library.search")}
          value={text}
          onChange={(e) => setText(e.target.value)}
          slotProps={{ input: { startAdornment: <InputAdornment position="start"><SearchIcon /></InputAdornment> } }}
        />
        <Stack direction="row" spacing={1} sx={{ minWidth: 0 }}>
          <TextField
            select
            size="small"
            label={t("library.sort")}
            value={sort}
            onChange={(e) => {
              const field = e.target.value as SortField;
              setSort(field);
              setDescending(defaultDescending(field));
              remember("library.sort", field);
              remember("library.descending", String(defaultDescending(field)));
            }}
            sx={{ flex: 1, minWidth: 0, maxWidth: { sm: 200 } }}
          >
            {sortFields.map((f) => (
              <MenuItem key={f} value={f}>{t(`library.sortBy.${f}`)}</MenuItem>
            ))}
          </TextField>
          <IconButton sx={{ flexShrink: 0 }} onClick={() => { setDescending(!descending); remember("library.descending", String(!descending)); }} aria-label="order">
            <SwapVertIcon sx={{ transform: descending ? undefined : "scaleY(-1)" }} />
          </IconButton>
          <TextField select size="small" label={t("library.filter")} value={filter} onChange={(e) => { setFilter(e.target.value as ProgressFilter); remember("library.filter", e.target.value); }} sx={{ flex: 1, minWidth: 0, maxWidth: { sm: 170 } }}>
            {progressFilters.map((f) => (
              <MenuItem key={f} value={f}>{t(`library.filters.${f}`)}</MenuItem>
            ))}
          </TextField>
        </Stack>
      </Stack>

      {(books.data?.length ?? 0) === 0 ? (
        <Box sx={{ textAlign: "center", py: 8 }}>
          <Typography variant="h6">{t("library.empty")}</Typography>
          <Typography color="text.secondary">{t("library.emptyHint")}</Typography>
        </Box>
      ) : items.length === 0 ? (
        <Typography color="text.secondary" sx={{ textAlign: "center", py: 6 }}>{t("library.nothingFound")}</Typography>
      ) : (
        <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "repeat(2, 1fr)", sm: "repeat(3, 1fr)", md: "repeat(5, 1fr)" } }}>
          {items.map((item) => {
            const book = item.entry.book;
            const isSeries = item.kind === "series";
            const progress = isSeries ? undefined : progressOf(item.entry);
            return (
              <Card key={isSeries ? `series:${item.series}` : book.id} variant="outlined" sx={{ border: 0, bgcolor: "transparent" }}>
                <CardActionArea onClick={() => navigate(isSeries ? `/series/${encodeURIComponent(item.series!)}` : `/book/${book.id}`)} sx={{ borderRadius: 2, p: 0.5 }}>
                  <Box sx={{ position: "relative" }}>
                    {isSeries && (
                      <Box sx={{ position: "absolute", inset: 0, transform: "translate(6px, -6px)", borderRadius: 1.5, bgcolor: "action.selected" }} />
                    )}
                    <BookCover book={book} progress={progress} />
                  </Box>
                  <Typography variant="body2" sx={{ fontWeight: 600, mt: 1, display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden" }}>
                    {isSeries ? item.series : book.metadata.title}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" noWrap component="div">
                    {isSeries ? `${book.metadata.author ?? ""} · ${t("library.booksInSeries", { count: item.books!.length })}` : book.metadata.author}
                  </Typography>
                </CardActionArea>
              </Card>
            );
          })}
        </Box>
      )}
    </Stack>
  );
}
