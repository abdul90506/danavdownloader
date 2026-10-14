#!/usr/bin/env python3
"""Vidra icon set — Google Material Symbols (Rounded, weight 400) + real brand logos (Simple Icons).
Writes res/drawable/ic_*.xml (native player/browser/notifications) and assets/app/icons.js (web UI).
Sources: npm @material-symbols/svg-400 and simple-icons, installed in ~/.cache/npmx (see README)."""
import os, re, json, subprocess
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app/src/main')
NM = os.path.expanduser('~/.cache/npmx/node_modules')
MS = f'{NM}/@material-symbols/svg-400/rounded'

# our name -> material symbol ("-fill" = filled variant)
I = {
 'play': 'play_arrow-fill', 'pause': 'pause-fill', 'next': 'skip_next-fill', 'prev': 'skip_previous-fill',
 'back': 'arrow_back', 'fwd': 'arrow_forward', 'rew10': 'replay_10', 'ff10': 'forward_10',
 'lock': 'lock-fill', 'unlock': 'lock_open_right', 'rotate': 'screen_rotation_alt', 'pip': 'picture_in_picture_alt',
 'aspect': 'fit_screen', 'subs': 'subtitles', 'audio': 'graphic_eq', 'volume': 'volume_up-fill', 'mute': 'volume_off-fill',
 'speed': 'speed', 'more': 'more_vert', 'repeat': 'repeat', 'repeat1': 'repeat_one', 'shuffle': 'shuffle',
 'sun': 'brightness_medium-fill', 'close': 'close', 'queue': 'queue_music', 'headphones': 'headphones', 'info': 'info',
 'camera': 'photo_camera', 'moon': 'bedtime', 'check': 'check', 'dl': 'download', 'shield': 'shield',
 'shieldOff': 'remove_moderator', 'shieldOn': 'verified_user-fill', 'reload': 'refresh', 'menu': 'more_vert',
 'desktop': 'desktop_windows', 'share': 'share', 'external': 'open_in_new', 'home': 'home', 'video': 'movie',
 'music': 'music_note', 'download': 'download', 'settings': 'settings', 'globe': 'language', 'search': 'search',
 'link': 'link', 'paste': 'content_paste', 'trash': 'delete', 'folder': 'folder', 'grid': 'grid_view',
 'list': 'view_agenda', 'sort': 'sort', 'album': 'album', 'user': 'person', 'plus': 'add', 'down': 'keyboard_arrow_down',
 'up': 'keyboard_arrow_up', 'chev': 'chevron_right', 'retry': 'refresh', 'cancel': 'close', 'eq': 'equalizer',
 'timer': 'timer', 'stat_music': 'music_note',
 # extra
 'homeF': 'home-fill', 'videoLib': 'video_library', 'videoLibF': 'video_library-fill', 'musicLib': 'library_music',
 'musicLibF': 'library_music-fill', 'dlOff': 'download_for_offline', 'dlOffF': 'download_for_offline-fill',
 'playCircle': 'play_circle-fill', 'copy': 'content_copy', 'history': 'history', 'storage': 'hard_drive',
 'tune': 'tune', 'hd': 'high_quality', 'bolt': 'bolt-fill', 'error': 'error', 'warn': 'warning', 'clock': 'schedule',
 'sparkle': 'star', 'cloudDone': 'download_done', 'tab': 'tab', 'star': 'star-fill', 'favorite': 'favorite',
 'audioFile': 'audio_file', 'image': 'image', 'pauseCircle': 'pause_circle-fill', 'stop': 'stop-fill', 'fullscreen': 'fullscreen',
 'fullscreenExit': 'fullscreen_exit', 'lockOpen': 'lock_open', 'brightness': 'brightness_medium', 'person': 'account_circle',
 'logout': 'logout', 'update': 'update', 'block': 'block', 'chevLeft': 'chevron_left', 'expand': 'keyboard_arrow_down',
 'arrowUp': 'north_east', 'file': 'description', 'backspace': 'backspace', 'key': 'key', 'restore': 'settings_backup_restore', 'palette': 'palette', 'textSize': 'format_size', 'contrast': 'contrast', 'photos': 'photo_library', 'newFolder': 'create_new_folder', 'private': 'shield_lock', 'folderF': 'folder-fill', 'musicF': 'music_note-fill', 'lockReset': 'lock_reset', 'deleteForever': 'delete_forever', 'move': 'drive_file_move', 'addPhoto': 'add_photo_alternate', 'dark': 'dark_mode', 'light': 'light_mode', 'checkCircle': 'check_circle-fill', 'fingerprint': 'fingerprint', 'playlist': 'playlist_play', 'playNext': 'playlist_add', 'clean': 'cleaning_services',
}
BRANDS = ['youtube', 'instagram', 'facebook', 'tiktok', 'x', 'vimeo', 'dailymotion', 'reddit', 'twitch', 'soundcloud', 'pinterest', 'google']

def path_of(name):
    s = open(f'{MS}/{name}.svg').read()
    ds = re.findall(r'<path d="([^"]+)"', s)
    assert ds, name
    return ' '.join(ds)

paths = {n: path_of(m) for n, m in I.items()}
os.makedirs(f'{ROOT}/res/drawable', exist_ok=True)
for n, d in paths.items():
    fname = 'ic_' + ''.join('_' + c.lower() if c.isupper() else c for c in n)
    open(f'{ROOT}/res/drawable/{fname}.xml', 'w').write(
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" '
        'android:viewportWidth="960" android:viewportHeight="960">\n'
        f'  <group android:translateY="960"><path android:fillColor="#FFFFFFFF" android:pathData="{d}"/></group>\n</vector>\n')

# brand logos: path + official colour (Simple Icons, 24x24)
js = subprocess.run(['node', '-e', f"""
const si=require('{NM}/simple-icons');const o={{}};
for(const s of {json.dumps(BRANDS)}){{const k='si'+s[0].toUpperCase()+s.slice(1);const i=si[k];o[s]={{d:i.path,hex:i.hex,title:i.title}};}}
process.stdout.write(JSON.stringify(o));"""], capture_output=True, text=True, check=True).stdout
brands = json.loads(js)
open(f'{ROOT}/assets/app/icons.js', 'w').write(
    '/* generated by tools/icons.py — Material Symbols Rounded (Apache-2.0) + Simple Icons brand marks (CC0) */\n'
    'window.VICONS=' + json.dumps(paths, separators=(',', ':')) + ';\n'
    'window.VBRANDS=' + json.dumps(brands, separators=(',', ':')) + ';\n')
print(len(paths), 'icons,', len(brands), 'brands')
