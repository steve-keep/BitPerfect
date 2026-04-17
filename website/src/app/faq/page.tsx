export default function FAQ() {
  const faqs = [
    {
      question: "What hardware do I need to use BitPerfect? 🔌",
      answer: "You need an Android device supporting USB OTG, a compatible USB CD/DVD drive, and a USB OTG adapter. For the ultimate playback experience, you&apos;ll also want a USB DAC."
    },
    {
      question: "Why do I need this? Can&apos;t I just rip on my PC? 🖥️",
      answer: "Of course you can! But BitPerfect lets you achieve EAC-level secure rips right on your phone or tablet. Perfect for ripping that rare thrift-store find while sitting in your car. 🚗💿"
    },
    {
      question: "What is &apos;Secure Ripping&apos;? 🛡️",
      answer: "It means we don&apos;t trust the CD drive. We read the same data multiple times, bust the hardware cache, and compare the results to ensure we have the absolute bit-perfect audio data, even if the disc is scratched."
    },
    {
      question: "Why does Android resample my audio? 🤬",
      answer: "By default, Android&apos;s AudioFlinger mixer resamples everything (often to 48kHz) to mix notifications and audio streams. BitPerfect uses specialized bypass strategies (like libusb isochronous transfers) to send pure 44.1kHz data directly to your DAC."
    },
    {
      question: "What are &apos;Read Offsets&apos;? 📏",
      answer: "Every CD drive starts reading a few samples too early or too late. It&apos;s a hardware quirk. BitPerfect uses the AccurateRip database to automatically detect your drive&apos;s offset and corrects it during extraction."
    },
    {
      question: "How do I read the Diagnostics? 🔬",
      answer: "The High-Fidelity Diagnostic dashboard shows you everything: C2 error pointer support, cache size, extraction speed, and real-time AccurateRip confidence. Green is good. 🟢"
    }
  ];

  return (
    <div className="max-w-4xl mx-auto px-4 py-16">
      <div className="text-center mb-12">
        <h1 className="text-4xl font-extrabold mb-4">Frequently Asked Questions 🤔</h1>
        <p className="text-muted text-lg">Everything you need to know about extracting bits perfectly.</p>
      </div>

      <div className="space-y-6">
        {faqs.map((faq, index) => (
          <div key={index} className="bg-surface p-6 rounded-xl border border-surface-elevated">
            <h3 className="text-xl font-bold text-primary mb-3">{faq.question}</h3>
            <p className="text-foreground leading-relaxed">{faq.answer}</p>
          </div>
        ))}
      </div>
    </div>
  );
}
