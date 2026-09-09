import type { Config } from 'tailwindcss';

const config: Config = {
  content: ['./src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ink: { 950: '#0b0f17', 900: '#10151f', 800: '#171e2c', 700: '#202a3d', 600: '#2b3850' },
        mint: { 400: '#34d399', 500: '#10b981', 600: '#059669' },
      },
    },
  },
  plugins: [],
};

export default config;
