export function routeAccessDecision(route, currentActor) {
  const roles = route.meta?.roles;
  if (!roles?.length || roles.includes(currentActor.role)) return true;
  return {
    path: "/disputes",
    query: { access: "reviewer-only" },
  };
}
