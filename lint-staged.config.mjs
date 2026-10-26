export default {
  'services/**/*.java': 'node scripts/format-java.mjs',
  'frontend/**/*.{js,mjs,cjs,jsx,ts,tsx,css,json,html,md,yaml,yml}':
    'node scripts/format-frontend.mjs',
};
