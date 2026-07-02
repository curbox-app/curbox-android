<div align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" style="width: 30%;" />
   <h2>Curbox</h2>
   
   [![GitHub contributors](https://img.shields.io/github/contributors/nethical6/curbox)](https://github.com/nethical6/curbox/graphs/contributors)
   [![Discord Server](https://img.shields.io/badge/Discord%20Server-white?style=flat&logo=discord)](https://discord.com/invite/Vs9mwUtuCN)
   [![Telegram Group](https://img.shields.io/badge/Telegram%20Group-blue?style=flat&logo=telegram)](https://t.me/curboxapp)

</div>

Curbox (formerly Digipauwse) is a powerful open source utility for Android built to help you reclaim your time and master your digital habits. It provides a suite of tools designed to break the cycle of screen addiction through granular control and deep insights.

<div align="center">
<table>
	<tr>
		<td><a href="https://trendshift.io/repositories/14634?utm_source=repository-badge&amp;utm_medium=badge&amp;utm_campaign=badge-repository-14634" target="_blank" rel="noopener noreferrer"><img src="https://trendshift.io/api/badge/repositories/14634" alt="curbox-app%2Fcurbox-android | Trendshift" width="250" height="55"/></a></td>
		<td><a href="https://trendshift.io/repositories/14634?utm_source=trendshift-badge&amp;utm_medium=badge&amp;utm_campaign=badge-trendshift-14634" target="_blank" rel="noopener noreferrer"><img src="https://trendshift.io/api/badge/trendshift/repositories/14634/daily?language=Kotlin" alt="curbox-app%2Fcurbox-android | Trendshift" width="250" height="55"/></a></td>
	</tr>
</table>
</div>

### Screenshots
Click on any image to enlarge it.

<div align="center">
<table>
	<tr>
		<td><img src='fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg' width='120'></td>
		<td><img src='fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg' width='120'></td>
		<td><img src='fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg' width='120'></td>
		<td><img src='fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg' width='120'></td>
	</tr>
</table>
</div>

### Why Curbox Stands Out

Most screen time tools are closed source and require internet access. This raises concerns about your private usage data being tracked or sold. Curbox takes a different path.
* All screen time apps use a powerful Android feature (Accessibility service) that lets them see everything you do, including your private messages. They all connect to the internet, posing a severe security threat. Curbox uses this same feature, but it does not have internet access (it doesn't declare the permission in the manifest). This means your personal data can never leave your phone, keeping it completely private and secure.
* Open Source Transparency
The entire codebase is public. Anyone can verify how the app works. This ensures there are no hidden trackers or malicious behaviors. Open source doesn't mean anyone can see how you use the app. 
* Respect for Your Data
Because there is no cloud connection, you are the sole owner of your statistics. There are literally a few companies selling your usage insights for millions so the algorithm can be further strengthened to keep you trapped.

### Platforms:
- Android (this repository)
- [Web Extension Repository](https://github.com/curbox-app/curbox-extension)
- IOS (comming soon)


### How to install
[Please read the docs for step by step instructions](https://curbox.app/docs)


 <p>
    <a href="https://f-droid.org/packages/neth.iecal.curbox"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80"></a>
    <a href="https://github.com/curbox-app/curbox-android/releases/latest"><img src="https://raw.githubusercontent.com/andOTP/andOTP/master/assets/badges/get-it-on-github.png" height="80"></a>
   </p>


### Independent Reviews & Endorsements

* **HowToMen (900k followers):** [Top 20 shizuku apps 2025](https://youtu.be/hGsPpd0uIco?t=123&si=I4wFOCA-ePm61XLk)
* **HowToMen (900k followers):** [Top 15 best android apps may 2025](https://youtu.be/_b-GRZEAENc?t=710&si=3L8m8WlS-qWoVX8C)
* **HowToMen (900k followers):** [Top 15 best android apps may 2026](https://youtu.be/B59glf2bweo?t=546&si=T2pUw2yZi9Krsv3A)
* **GadgetGeek (4k followers):** [Awesome Free and open source apps](https://youtu.be/rAQ85K337l8?t=1148&si=U2ZmJe7CALvYAuGE)
* **Sam Beckman (600k followers):** [Best free and open source apps 2026](https://youtu.be/bR19smAQfUs?t=624&si=DXNLiF-F1pY4neVq)
* **Sam Beckman (600k followers):** [This app makes your phone less distracting](https://youtu.be/ncc7Ho9cNLw?si=e5BFCPvh4FbFqDFC)
* **justtechit.english (462k followers):** [Open Source App Hunt Part 2](https://www.instagram.com/reel/DY4dJbVMn4p/)
* **Sohail TECH (16k followers):** [5 Best & POWERFUL Android Apps in 2025](https://www.youtube.com/watch?v=u8B6TihqlPs)
* **Anubhav Roy (340k followers):** [Best Free Open Source Android Apps in 2026](https://www.youtube.com/watch?v=M2ufnTybmH4)
* **El Androide Feliz (50k followers):** [The BEST APPS compatible with SHIZUKU](https://www.youtube.com/watch?v=rps2uw8Cokw)
* **Techy Marathi (723k followers):** [Instagram Reel](https://www.instagram.com/reel/DJjcOc8yKOR/)
* **Sillycorns (700k followers):** [Top 10 Coolest Android Apps of 2025!](https://youtu.be/FWn5ZEyqdvQ?t=273&si=hocsC2GTdYPPmNRK)

Please open an issue if your review is not included, Thank you so much!


### In-Depth Feature Analysis

Curbox goes beyond simple app timers. It targets the specific triggers that lead to mindless scrolling.

#### Blocking & Control
- **App & Website Blocking:** Block distracting apps and websites completely.
- **Short-Form Content Blocker:** Specifically target Instagram Reels and YouTube Shorts.
- **Granular UI Hiding:** Block specific parts of an app's UI (e.g., block the entire YouTube home feed while allowing searches or hide instagram feed while allowing posts and messages from friends).
- **URL Path Blocking:** Block specific sections of a website (e.g., block `m.youtube.com/shorts/*` but allow `m.youtube.com`).

#### Insights & Awareness
- **Usage Analytics:** Detailed insights into app and website usage.
- **Focus Statistics:** Track your progress and focus sessions.
- **Live Scroll Counter:** See a real-time count of how many short-form videos you've scrolled through.
- **Session Timer:** A live timer showing how much time has elapsed since you opened a given app.

#### Mindful Usage Tools
- **Focus Mode:** Temporarily pause apps and websites to stay focused on your work.
- **Scheduled DND:** Schedule Do Not Disturb to turn on automatically.
- **App-Specific Grayscale:** Apply a grayscale filter only to specific apps (e.g., Instagram) while keeping others (e.g., Camera) in color.
- **Usage-Based Limits:** Block apps or websites based on usage (e.g., block WhatsApp after 1 hour of use).
- **Time-Based Access:** Allow apps only during specific hours (e.g., WhatsApp only between 7 AM and 9 PM).
- **Home Screen Widgets:** Quick access to your stats and controls right from your home screen.

#### Unlock Mechanisms
Define what happens when you try to open an app outside its allowed schedule:
- **Strict Blockade:** Complete blockade with no access.
- **Timed Unlocks:** Predefine a short duration (e.g., 5 mins) for each subsequent unlock.
- **Dynamic Selection:** Choose the duration of your temporary unlock on each attempt.
- **QR/Barcode Unlock:** Requires scanning a physical QR code or barcode (e.g., from product boxes like books) to unlock. Spread them across your home to force physical movement!
- **Sentence Retyping:** Requires physically typing a specific sentence (e.g., "I am giving up on my goals to use this app right now") to unlock.
- **Unlock Limits:** Set how many subsequent unlocks you can perform every X hours.

#### For Developers
- **[Curbox API](CURBOX_API.md):** Let your apps directly interact with curbox and start focus modes, change settings, get usage stats, etc. Requires user permission

### Contributing

We welcome contributions from the community! If you want to help improve Curbox, please follow these steps:

1. Fork the repository.
2. Create a new branch for your feature or bugfix.
3. Commit your changes with a clear message.
4. Push to your branch.
5. Create a new Pull Request.

Developing for accessibility services and blockers is complex. It requires understanding how different apps structure their views. We appreciate any help in discovering new ways to block distracting content.
Please don't open a PR if you "vibe code" and have no idea what you're doing or what the code does. Preferably, just open an issue with the feature request.

Please read the [CONTRIBUTING.md](CONTRIBUTING.md) file before working on the project or learning
how to make quick contributions like adding support for a modded app in the reel counter, adding support
for a new browser, etc.


### Special Thanks

* All my beloved donors and sponsors
* Digipauws: Inspiration for the entire code structure and working mechanism
* Usage Direct: For help with app usage statistics.
* Redd Focus: For the foundation of the view blocker system.
* ShizuTools: For Shizuku runner implementations.
* MPAndroidChart: For the beautiful graphs and charts.

### License

Curbox is licensed under the GPL 3 or later license. You are free to use, modify, and distribute this software in accordance with the license.

### Contact

For questions or feedback, please open an issue on the GitHub repository or reach out:
* Discord: @nethical
* Telegram: @nethicalps
* Email: [aguptaq88@gmail.com](aguptaq88@gmail.com)

### TAGS (ignore em pls)

<details>
<summary>Keywords</summary>

app blocker, free app blocker, open source app blocker, screen time app, screen time tracker, digital wellbeing app, focus app, productivity app, website blocker, porn blocker, adult content blocker, distraction blocker, social media blocker, Instagram blocker, Instagram Reels blocker, YouTube Shorts blocker, TikTok blocker, Snapchat Spotlight blocker, short form video blocker, doomscrolling blocker, reel counter, scroll counter, dopamine detox app, digital detox app, phone addiction app, screen addiction app, internet addiction app, self control app, focus mode app, deep work app, study focus app, grayscale app, app usage tracker, usage stats app, schedule DND app, app timer, app limit app, time limit app.

StayFree alternative, Opal alternative, Freedom app alternative, Forest app alternative, Cold Turkey alternative, AppBlock alternative, ScreenZen alternative, one sec alternative, BlockSite alternative, Stay Focused alternative, ActionDash alternative, Digital Wellbeing alternative, Brick alternative, Unpluq alternative, Refocus alternative, Roots app alternative, Clearspace alternative, Jomo alternative, LockMeOut alternative, Detox app alternative, ZenScreen alternative, Minimalist Phone alternative, RescueTime alternative, Offtime alternative, Flipd alternative, Forest focus alternative, free StayFree alternative, open source Freedom alternative, no subscription app blocker, offline app blocker, private screen time app, app blocker without internet, Android app blocker, F-Droid app blocker, Shizuku app blocker.

</details>
