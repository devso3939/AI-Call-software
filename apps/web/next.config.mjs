/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  output: 'standalone',
  async rewrites() {
    // Proxy API + WS to the backend so the web app works on any host (localhost/LAN)
    // without hardcoding the API origin in client code.
    return [
      { source: '/v1/:path*', destination: 'http://127.0.0.1:4000/v1/:path*' },
      { source: '/health', destination: 'http://127.0.0.1:4000/health' },
      { source: '/ready', destination: 'http://127.0.0.1:4000/ready' },
      { source: '/ws', destination: 'http://127.0.0.1:4000/ws' },
    ];
  },
};

export default nextConfig;
