/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/**/*.{html,ts}'],
  theme: {
    extend: {
      colors: {
        // #405 — la même identité (vert de la faculté sur crème), affinée : une
        // vraie échelle de verts pour les états, pas seulement trois tons.
        brand: {
          50: '#eaf5ee',
          100: '#d5eadd',
          200: '#b5d9c3',
          300: '#8cc2a2',
          DEFAULT: '#1f5e3a',
          light: '#2d8050',
          dark: '#164a2c',
          700: '#164a2c',
          900: '#0f3322',
        },
        surface: {
          DEFAULT: '#fbf6e8',
          card: '#ffffff',
          muted: '#f5f1e3',
          2: '#f7f1df',
        },
        status: {
          success: '#16a34a',
          danger: '#dc2626',
          warning: '#f59e0b',
          info: '#0ea5e9',
        },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'sans-serif'],
      },
      boxShadow: {
        // Une élévation LISIBLE (teintée du vert sombre, pas d'un gris neutre) :
        // l'ancienne ombre à 5 % était invisible sur le fond crème.
        card: '0 1px 2px 0 rgb(15 51 34 / 0.06), 0 1px 3px 0 rgb(15 51 34 / 0.08)',
        pop: '0 12px 32px -12px rgb(15 51 34 / 0.28)',
        ring: '0 0 0 3px rgb(31 94 58 / 0.18)',
      },
      borderRadius: {
        xl: '0.875rem',
        '2xl': '1.125rem',
      },
    },
  },
  plugins: [],
};
