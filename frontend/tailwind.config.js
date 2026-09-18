/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        // AWS Console-inspired palette
        'squid-ink': '#232f3e',
        'anchor': '#1b2a3b',
        'aws-orange': '#ff9900',
        'aws-orange-hover': '#ec7211',
        'aws-smile': '#ffb356',
        'aws-blue': '#0073bb',
        'aws-blue-hover': '#006ab8',
        'surface': '#f2f3f3',
        'surface-alt': '#fafafa',
      },
    },
  },
  plugins: [],
};
