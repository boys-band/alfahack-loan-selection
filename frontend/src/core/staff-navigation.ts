import { useLocation } from 'react-router-dom';
import type { Application } from '../api/generated';

export function currentDecision(app: Application) {
  return app.decision?.dataVersion === app.dataVersion ? app.decision : null;
}

export function approvalLabel(app: Application) {
  const decision = currentDecision(app);
  if (!decision) return '—';
  if (decision.outcome === 'APPROVED')
    return decision.kind === 'AUTO' ? 'Автоматически одобрено' : 'Одобрено сотрудником';
  const outcome = {
    REJECTED: 'Отказано',
    NEEDS_INFO: 'Требуются дополнительные сведения',
    MANUAL_REVIEW: 'Требуется рассмотрение сотрудником',
  }[decision.outcome];
  return `${decision.kind === 'AUTO' ? 'Автоматическое' : 'Ручное'} решение · ${outcome}`;
}

export function staffSource(value: string | null) {
  if (!value || !/^\/staff\/(applications|manual-reviews)(\?|$)/.test(value))
    return '/staff/applications';
  // Only internal registry/queue locations, never arbitrary redirect targets.
  return value;
}

export function useStaffNavigation(id: string) {
  const location = useLocation();
  const backTo = staffSource(new URLSearchParams(location.search).get('from'));
  const suffix = '?from=' + encodeURIComponent(backTo);
  return { backTo, suffix, base: '/staff/applications/' + id };
}
