import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "export",
  basePath: "/BitPerfect",
  images: {
    unoptimized: true,
  },
};

export default nextConfig;
