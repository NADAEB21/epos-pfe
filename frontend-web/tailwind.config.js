/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/**/*.{html,ts}'],
  theme: {
    extend: {
      colors: {
        brand: {
          DEFAULT: '#1f5e3a',
          dark: '#164a2c',
          light: '#2d8050',
          50: '#eaf5ee',
        },
        surface: {
          DEFAULT: '#fbf6e8',
          card: '#ffffff',
          muted: '#f5f1e3',
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
        card: '0 1px 3px 0 rgb(0 0 0 / 0.05), 0 1px 2px -1px rgb(0 0 0 / 0.03)',
      },
    },
  },
  plugins: [],
};
