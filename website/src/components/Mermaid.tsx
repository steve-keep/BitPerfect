"use client";

import React, { useEffect, useRef, useState, useId } from 'react';
import mermaid from 'mermaid';

interface MermaidProps {
  chart: string;
}

export default function Mermaid({ chart }: MermaidProps) {
  const [svgCode, setSvgCode] = useState('');
  const uniqueId = useId();
  const id = useRef(`mermaid-${uniqueId.replace(/:/g, '')}`);

  useEffect(() => {
    mermaid.initialize({
      startOnLoad: false,
      theme: 'dark',
      themeVariables: {
        primaryColor: '#1e1e1e',
        primaryTextColor: '#ededed',
        primaryBorderColor: '#00E676',
        lineColor: '#ededed',
        secondaryColor: '#2c2c2c',
        tertiaryColor: '#121212'
      }
    });

    const renderChart = async () => {
      try {
        const { svg } = await mermaid.render(id.current, chart);
        setSvgCode(svg);
      } catch (error) {
        console.error('Mermaid rendering error:', error);
      }
    };

    renderChart();
  }, [chart]);

  return (
    <div
      className="mermaid-container flex justify-center p-4 bg-surface-elevated rounded-lg my-6 overflow-x-auto border border-surface-elevated"
      dangerouslySetInnerHTML={{ __html: svgCode }}
    />
  );
}
