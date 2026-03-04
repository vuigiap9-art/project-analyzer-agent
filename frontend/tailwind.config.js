/** @type {import('tailwindcss').Config} */
export default {
    content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
    darkMode: 'class',
    theme: {
        extend: {
            colors: {
                cyber: {
                    50: '#e0fffe',
                    100: '#b3fffc',
                    200: '#80fff9',
                    300: '#4dfff6',
                    400: '#00ffe5',
                    500: '#00e5cc',
                    600: '#00b3a0',
                    700: '#008075',
                    800: '#004d46',
                    900: '#001a18',
                },
                dark: {
                    50: '#f5f5f6',
                    100: '#e6e6e8',
                    200: '#ccced1',
                    300: '#a8abb1',
                    400: '#7c8089',
                    500: '#61656e',
                    600: '#52555d',
                    700: '#46484e',
                    800: '#1e1f23',
                    850: '#18191d',
                    900: '#121316',
                    950: '#0a0b0d',
                },
            },
            fontFamily: {
                mono: ['"JetBrains Mono"', 'Fira Code', 'monospace'],
                sans: ['Inter', 'system-ui', 'sans-serif'],
            },
            animation: {
                'pulse-glow': 'pulseGlow 2s ease-in-out infinite',
                'slide-up': 'slideUp 0.3s ease-out',
                'fade-in': 'fadeIn 0.4s ease-out',
                'scan-line': 'scanLine 3s linear infinite',
                'typing': 'typing 1.5s steps(3) infinite',
            },
            keyframes: {
                pulseGlow: {
                    '0%, 100%': { boxShadow: '0 0 5px rgba(0, 229, 204, 0.3)' },
                    '50%': { boxShadow: '0 0 20px rgba(0, 229, 204, 0.6), 0 0 40px rgba(0, 229, 204, 0.2)' },
                },
                slideUp: {
                    '0%': { transform: 'translateY(10px)', opacity: '0' },
                    '100%': { transform: 'translateY(0)', opacity: '1' },
                },
                fadeIn: {
                    '0%': { opacity: '0' },
                    '100%': { opacity: '1' },
                },
                scanLine: {
                    '0%': { transform: 'translateY(-100%)' },
                    '100%': { transform: 'translateY(100%)' },
                },
                typing: {
                    '0%': { content: '"."' },
                    '33%': { content: '".."' },
                    '66%': { content: '"..."' },
                },
            },
        },
    },
    plugins: [],
}
