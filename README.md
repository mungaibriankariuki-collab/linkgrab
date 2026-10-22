# LinkGrab

Android app: paste or share a link, then download it as a **video (MP4)**, a **song (MP3)**, or a **direct .apk file**.

Video and song downloads use [yt-dlp](https://github.com/yt-dlp/yt-dlp) through
[youtubedl-android](https://github.com/JunkFood02/youtubedl-android), so YouTube, TikTok, Instagram, X,
Facebook, SoundCloud and 1000+ other sites work. Files are saved to `Download/LinkGrab`.

## Get the APK

GitHub Actions builds the app on every push to `main`. Open the repo's **Releases** page and download
`LinkGrab.apk` from the newest build. Allow "Install unknown apps" for your browser or file manager when asked.

## Use it

- Paste a link and tap **Download**, or
- In YouTube / TikTok / a browser, tap **Share → LinkGrab**.

If a site stops working, tap **Update downloader engine**. The app also checks for a new yt-dlp each time it opens.

Only download content you own or have permission to save.
