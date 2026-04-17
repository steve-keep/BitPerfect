# 💿✨ BitPerfect ✨💿

<p align="center">
  <img src="assets/logo.jpg" alt="BitPerfect Logo" width="200" />
</p>

Welcome to **BitPerfect**! The ultimate, absolute, no-compromise, bit-obsessed CD ripping app for Android! 📱🎧

[🌐 Check out our Website!](https://steve-keep.github.io/BitPerfect/)

Are you tired of wondering if that scratch on track 4 of your rare Japanese import of *Dark Side of the Moon* ruined the audio? 😱 Do you lie awake at night sweating over drive read offsets and cache flushing? 😰 Well, grab your OTG cable and your favorite USB CD drive, because we're about to get **BIT PERFECT**! 🚀🔥

## 🎵 What is this witchcraft? 🧙‍♂️

BitPerfect is an Android app that lets you plug a USB CD/DVD drive directly into your phone or tablet and rip your precious silver discs to glorious, lossless **FLAC** format. 🎶📁

But wait, there's more! We don't just "rip" CDs like it's 2004. Oh no. We **VERIFY** them! 🕵️‍♂️🔍

### 🌟 Features that will make your audiophile heart sing 🌟

* **AccurateRip™ Integration**: We check your rips against the legendary AccurateRip database (v1 & v2 algorithms!) to ensure your copy is mathematically identical to thousands of others. 💯✅
* **Drive Capability Detection**: We interrogate your USB drive like a 90s action hero. We find out if it supports *Accurate Stream*, *C2 Error Pointers*, and if it has a *Cache*. 🕵️‍♀️💿
* **Cache Busting**: Oh, your drive thinks it's smart by caching audio data? 🧠 We do decoy reads to flush that cache and force the drive to *actually* read the disc during multi-pass secure reads! Take that, firmware! 💥🤛
* **Drive Offsets**: We handle read offsets automatically. We even auto-calibrate! 🎛️📐
* **Lossless FLAC Encoding**: Encoded using Android's native MediaCodec for that sweet, sweet compression without losing a single zero or one. 📉💎
* **Metadata Fetching**: Because nobody wants to listen to `Track01.flac`. We fetch album art and tags so your library looks as good as it sounds. 🖼️🏷️
* **USB OTG Magic**: Just plug in your mass storage CD drive and watch the magic happen. 🪄📲
* **Storage Access Framework**: Save your precious FLACs wherever you want—internal storage, SD card, or your secret audiophile vault. 💾🏦

## 🛠️ How to use (The Ritual) 🕯️

1. **Get the app**: Install BitPerfect on your Android device. 📱
2. **Get the hardware**: Find a USB CD/DVD drive and an OTG (On-The-Go) adapter. 🔌
3. **The Connection**: Plug the drive into your phone. (Grant those USB permissions like a boss! 👑)
4. **The Offering**: Insert a CD. 💿
5. **The Magic**: Watch the app read the TOC (Table of Contents), fetch metadata, and prepare for extraction. 📊
6. **The Rip**: Hit "Start Secure Rip" and watch the sectors fly by. 🏎️💨
7. **The Validation**: Bask in the glory of the AccurateRip verification match. 🏆🎧

## 🤓 For the Nerds (You know who you are) 🤓

Under the hood, BitPerfect speaks fluent SCSI commands (like `READ TOC`, `TEST UNIT READY`, and `READ CD`). We handle MMC-compliant ADR and Control parsing, MSF/LBA conversions, and we even have a Virtual SCSI Driver for testing! 🧪💻

If you speak Kotlin, Coroutines, and Ktor, you're going to love this codebase. 🤖❤️

## 🤝 Contributing 🤝

Found a bug? 🐛 Want to add support for some obscure 1980s drive? 💾 Open an issue or send a pull request! We love fellow bit-chasers. 🏃‍♂️💨

---

*Now go forth and rip your CDs perfectly! Your ears will thank you.* 🎧👂✨
