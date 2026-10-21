import { Box, CircularProgress } from "@mui/material";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { setUnauthorizedHandler } from "./api/client";
import { keys, useMe, useServerEvents, useStatus } from "./api/hooks";
import AppShell from "./components/AppShell";
import { PlayerProvider } from "./player/PlayerContext";
import AccountPage from "./pages/AccountPage";
import AdminPage from "./pages/AdminPage";
import BookPage from "./pages/BookPage";
import ImportsPage from "./pages/ImportsPage";
import LibraryPage from "./pages/LibraryPage";
import LoginPage from "./pages/LoginPage";
import PlayerPage from "./pages/PlayerPage";
import SeriesPage from "./pages/SeriesPage";

export default function App() {
  const status = useStatus();
  const me = useMe();
  const client = useQueryClient();
  const navigate = useNavigate();

  useEffect(() => {
    setUnauthorizedHandler(() => {
      client.setQueryData(keys.me, null);
      client.removeQueries({ predicate: (q) => q.queryKey[0] !== "status" && q.queryKey[0] !== "me" });
      navigate("/login");
    });
  }, [client, navigate]);

  const session = me.data ?? null;
  useServerEvents(!!session);

  if (status.isLoading || me.isLoading) {
    return (
      <Box sx={{ display: "grid", placeItems: "center", height: "100dvh" }}>
        <CircularProgress />
      </Box>
    );
  }

  if (!session) {
    return (
      <Routes>
        <Route path="/login" element={<LoginPage setup={!!status.data?.setupRequired} />} />
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    );
  }

  return (
    <PlayerProvider deviceId={session.deviceId}>
      <AppShell session={session} features={status.data?.features ?? []}>
        <Routes>
          <Route path="/" element={<LibraryPage />} />
          <Route path="/series/:name" element={<SeriesPage />} />
          <Route path="/book/:id" element={<BookPage />} />
          <Route path="/player" element={<PlayerPage />} />
          <Route path="/add" element={<ImportsPage torrents={status.data?.features.includes("torrents") ?? false} />} />
          <Route path="/account" element={<AccountPage session={session} />} />
          {session.user.isAdmin && <Route path="/admin" element={<AdminPage />} />}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AppShell>
    </PlayerProvider>
  );
}
