#import "ARPlayer.h"
#import "ARTrack.h"
#import "ARProxyClient.h"
#import "ARArtwork.h"
#import "ARStore.h"
#import "ARSettings.h"
#import "ARYouTube.h"
#import <AVFoundation/AVFoundation.h>
#import <MediaPlayer/MediaPlayer.h>
#import <UIKit/UIKit.h>

NSString *const ARPlayerDidChangeNotification = @"ARPlayerDidChangeNotification";
NSString *const ARPlayerTimeNotification = @"ARPlayerTimeNotification";

static void *ARItemContext = &ARItemContext;
static void *ARRateContext = &ARRateContext;

@implementation ARPlayer {
    AVPlayer *_player;
    AVPlayerItem *_item;
    id _timeObserver;
    NSMutableArray<ARTrack *> *_queue;
    NSMutableArray<ARTrack *> *_unshuffled;
    BOOL _wantsPlay;
    BOOL _resumeAfterInterruption;
    NSUInteger _loadToken;     // ignores stream lookups for a track that is no longer current
    BOOL _resolving;           // looking up the stream URL
    BOOL _viaProxy;            // the current stream comes through the proxy
    BOOL _triedProxyFallback;
    NSTimeInterval _pendingSeek; // where to resume once a reloaded stream is ready...
    __weak ARTrack *_pendingSeekTrack; // ...for this track only
    UIImage *_artwork;
}

+ (instancetype)shared {
    static ARPlayer *player;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ player = [ARPlayer new]; });
    return player;
}

- (instancetype)init {
    if ((self = [super init])) {
        _queue = [NSMutableArray array];
        _player = [AVPlayer new];
        _player.actionAtItemEnd = AVPlayerActionAtItemEndPause;
        [_player addObserver:self forKeyPath:@"rate" options:0 context:ARRateContext];

        __weak typeof(self) weakSelf = self;
        _timeObserver = [_player addPeriodicTimeObserverForInterval:CMTimeMakeWithSeconds(0.5, 600) queue:dispatch_get_main_queue() usingBlock:^(CMTime time) {
            [weakSelf skipSegmentIfNeeded];
            [[NSNotificationCenter defaultCenter] postNotificationName:ARPlayerTimeNotification object:weakSelf];
        }];

        _shuffle = [[NSUserDefaults standardUserDefaults] boolForKey:@"ARShuffle"];
        _repeatMode = [[NSUserDefaults standardUserDefaults] integerForKey:@"ARRepeat"];

        AVAudioSession *session = [AVAudioSession sharedInstance];
        [session setCategory:AVAudioSessionCategoryPlayback error:nil];
        [session setActive:YES error:nil];

        NSNotificationCenter *nc = [NSNotificationCenter defaultCenter];
        [nc addObserver:self selector:@selector(itemDidEnd:) name:AVPlayerItemDidPlayToEndTimeNotification object:nil];
        [nc addObserver:self selector:@selector(itemFailed:) name:AVPlayerItemFailedToPlayToEndTimeNotification object:nil];
        [nc addObserver:self selector:@selector(interruption:) name:AVAudioSessionInterruptionNotification object:session];
        [nc addObserver:self selector:@selector(routeChange:) name:AVAudioSessionRouteChangeNotification object:session];

        [self setUpRemoteCommands];
    }
    return self;
}

#pragma mark - State

- (NSArray<ARTrack *> *)queue { return [_queue copy]; }

- (ARTrack *)currentTrack {
    return (_index >= 0 && _index < (NSInteger)_queue.count) ? _queue[_index] : nil;
}

- (BOOL)isPlaying { return _wantsPlay; }

- (BOOL)isBuffering {
    return _wantsPlay && !_errorMessage && (_resolving || (_item && _player.rate == 0));
}

- (NSTimeInterval)currentTime {
    NSTimeInterval t = CMTimeGetSeconds(_player.currentTime);
    return isnan(t) ? 0 : t;
}

- (NSTimeInterval)duration {
    if (_item.status == AVPlayerItemStatusReadyToPlay) {
        NSTimeInterval d = CMTimeGetSeconds(_item.duration);
        if (!isnan(d) && !isinf(d) && d > 0) return d;
    }
    return self.currentTrack.duration;
}

- (void)changed {
    [self updateNowPlayingInfo];
    [[NSNotificationCenter defaultCenter] postNotificationName:ARPlayerDidChangeNotification object:self];
}

#pragma mark - Queue

- (void)playTracks:(NSArray<ARTrack *> *)tracks startIndex:(NSInteger)index {
    NSMutableArray *playable = [NSMutableArray array];
    NSInteger start = 0;
    for (NSInteger i = 0; i < (NSInteger)tracks.count; i++) {
        if (!tracks[i].playable) continue;
        if (i == index) start = playable.count;
        [playable addObject:tracks[i]];
    }
    if (playable.count == 0) return;
    if (index < 0) start = arc4random_uniform((uint32_t)playable.count);
    _queue = playable;
    _index = start;
    _unshuffled = nil;
    if (_shuffle) [self shuffleKeepingCurrent];
    [self loadCurrentAndPlay:YES];
}

- (void)shuffleKeepingCurrent {
    _unshuffled = [_queue mutableCopy];
    ARTrack *current = self.currentTrack;
    NSMutableArray *rest = [_queue mutableCopy];
    if (current) [rest removeObjectAtIndex:_index];
    for (NSUInteger i = rest.count; i > 1; i--) {
        [rest exchangeObjectAtIndex:i - 1 withObjectAtIndex:arc4random_uniform((uint32_t)i)];
    }
    if (current) [rest insertObject:current atIndex:0];
    _queue = rest;
    _index = 0;
}

- (void)setShuffle:(BOOL)shuffle {
    if (_shuffle == shuffle) return;
    _shuffle = shuffle;
    [[NSUserDefaults standardUserDefaults] setBool:shuffle forKey:@"ARShuffle"];
    if (shuffle) {
        if (_queue.count) [self shuffleKeepingCurrent];
    } else if (_unshuffled) {
        ARTrack *current = self.currentTrack;
        _queue = _unshuffled;
        _unshuffled = nil;
        NSUInteger i = current ? [_queue indexOfObjectIdenticalTo:current] : NSNotFound;
        _index = i == NSNotFound ? 0 : i;
    }
    [self changed];
}

- (void)setRepeatMode:(ARRepeatMode)repeatMode {
    _repeatMode = repeatMode;
    [[NSUserDefaults standardUserDefaults] setInteger:repeatMode forKey:@"ARRepeat"];
    [self changed];
}

- (void)playNext:(ARTrack *)track {
    if (!track.playable) return;
    if (_queue.count == 0) return [self playTracks:@[track] startIndex:0];
    ARTrack *current = self.currentTrack;
    [_queue insertObject:track atIndex:_index + 1];
    if (_unshuffled) {
        NSUInteger i = [_unshuffled indexOfObjectIdenticalTo:current];
        [_unshuffled insertObject:track atIndex:i == NSNotFound ? _unshuffled.count : i + 1];
    }
    [self changed];
}

- (void)enqueue:(ARTrack *)track {
    if (!track.playable) return;
    if (_queue.count == 0) return [self playTracks:@[track] startIndex:0];
    [_queue addObject:track];
    [_unshuffled addObject:track];
    [self changed];
}

- (void)jumpToIndex:(NSInteger)index {
    if (index < 0 || index >= (NSInteger)_queue.count) return;
    _index = index;
    [self loadCurrentAndPlay:YES];
}

- (void)removeTrackAtIndex:(NSInteger)index {
    if (index < 0 || index >= (NSInteger)_queue.count || index == _index) return;
    ARTrack *track = _queue[index];
    [_queue removeObjectAtIndex:index];
    [_unshuffled removeObjectIdenticalTo:track];
    if (index < _index) _index--;
    [self changed];
}

#pragma mark - Loading

- (void)observeItem:(AVPlayerItem *)item {
    if (_item) {
        for (NSString *key in @[@"status", @"playbackLikelyToKeepUp"]) [_item removeObserver:self forKeyPath:key context:ARItemContext];
    }
    _item = item;
    for (NSString *key in @[@"status", @"playbackLikelyToKeepUp"]) [_item addObserver:self forKeyPath:key options:0 context:ARItemContext];
}

- (void)loadCurrentAndPlay:(BOOL)play {
    ARTrack *track = self.currentTrack;
    NSUInteger token = ++_loadToken;
    _errorMessage = nil;
    _artwork = nil;
    _triedProxyFallback = NO;
    [self observeItem:nil];
    [_player replaceCurrentItemWithPlayerItem:nil];
    _wantsPlay = play && track != nil;

    if (!track) {
        _resolving = NO;
        [self changed];
        return;
    }
    if (track.source == ARTrackSourceLocal) {
        _resolving = NO;
        [self startTrack:track url:track.assetURL headers:nil play:play];
        return;
    }
    // YouTube: find the stream first (from YouTube itself or the proxy, per the Streaming setting)
    _resolving = YES;
    [self changed];
    [[ARStore shared] addRecent:track];
    [self fetchInfoForTrack:track];
    [ARYouTube streamForTrack:track forceProxy:NO completion:^(NSURL *url, NSDictionary *headers, BOOL viaProxy, NSError *error) {
        if (token != self->_loadToken) return;
        self->_resolving = NO;
        if (!url) {
            self->_errorMessage = error.localizedDescription ?: @"Couldn't find a stream for this song.";
            self->_wantsPlay = NO;
            [self changed];
            return;
        }
        self->_viaProxy = viaProxy;
        [self startTrack:track url:url headers:headers play:self->_wantsPlay];
    }];
}

- (void)startTrack:(ARTrack *)track url:(NSURL *)url headers:(NSDictionary *)headers play:(BOOL)play {
    // AVURLAssetHTTPHeaderFieldsKey isn't in the public headers, but AVFoundation has honored it since iOS 8.
    NSDictionary *options = headers.count ? @{@"AVURLAssetHTTPHeaderFieldsKey": headers} : nil;
    AVPlayerItem *item = [AVPlayerItem playerItemWithAsset:[AVURLAsset URLAssetWithURL:url options:options]];
    [self observeItem:item];
    [_player replaceCurrentItemWithPlayerItem:item];
    if (_pendingSeekTrack == track && _pendingSeek > 1) [_player seekToTime:CMTimeMakeWithSeconds(_pendingSeek, 600)];
    _pendingSeek = 0;
    _pendingSeekTrack = nil;
    _wantsPlay = play;
    if (play) {
        [[AVAudioSession sharedInstance] setActive:YES error:nil];
        [_player play];
    }
    [ARArtwork loadArtworkForTrack:track size:300 completion:^(UIImage *image) {
        if (self.currentTrack != track) return;
        self->_artwork = image;
        [self updateNowPlayingInfo];
    }];
    [self changed];
}

/// A direct stream that fails (YouTube refused it, say) gets one retry through the proxy.
- (BOOL)retryThroughProxy {
    ARTrack *track = self.currentTrack;
    if (track.source != ARTrackSourceYouTube || _viaProxy || _triedProxyFallback || ![ARYouTube canFallBackToProxy]) return NO;
    _triedProxyFallback = YES;
    NSUInteger token = _loadToken;
    BOOL play = _wantsPlay;
    _resolving = YES;
    [ARYouTube streamForTrack:track forceProxy:YES completion:^(NSURL *url, NSDictionary *headers, BOOL viaProxy, NSError *error) {
        if (token != self->_loadToken) return;
        self->_resolving = NO;
        self->_viaProxy = YES;
        if (url) [self startTrack:track url:url headers:nil play:play];
    }];
    return YES;
}

- (void)observeValueForKeyPath:(NSString *)keyPath ofObject:(id)object change:(NSDictionary *)change context:(void *)context {
    if (context != ARItemContext && context != ARRateContext) {
        return [super observeValueForKeyPath:keyPath ofObject:object change:change context:context];
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        if (context == ARItemContext && object != self->_item) return;
        if (self->_item.status == AVPlayerItemStatusFailed) {
            if ([self retryThroughProxy]) return;
            self->_errorMessage = self->_item.error.localizedDescription ?: @"This track couldn't be played.";
            self->_wantsPlay = NO;
        } else if (self->_wantsPlay && self->_player.rate == 0 && self->_item.playbackLikelyToKeepUp) {
            // AVPlayer stops by itself when a stream runs dry. Start again once there is enough buffered.
            [self->_player play];
        }
        [self changed];
    });
}

- (BOOL)skipsNonMusic { return [ARSettings shared].skipNonMusic; }
- (void)setSkipsNonMusic:(BOOL)skipsNonMusic { [ARSettings shared].skipNonMusic = skipsNonMusic; }

/// Asks the proxy for the real song title, artist, album, album art and the parts to skip.
- (void)fetchInfoForTrack:(ARTrack *)track {
    [ARYouTube infoForTrack:track completion:^(NSDictionary *info) {
        if (!info) return; // keep what search gave us
        NSString *title = [info[@"title"] isKindOfClass:[NSString class]] ? info[@"title"] : nil;
        NSString *artist = [info[@"artist"] isKindOfClass:[NSString class]] ? info[@"artist"] : nil;
        NSString *album = [info[@"album"] isKindOfClass:[NSString class]] ? info[@"album"] : nil;
        if (title.length) track.title = title;
        if (artist.length) track.artist = artist;
        if (album) track.album = album;
        NSMutableArray *segments = [NSMutableArray array];
        for (NSArray *seg in info[@"skip"]) {
            if ([seg isKindOfClass:[NSArray class]] && seg.count == 2) [segments addObject:@[@([seg[0] doubleValue]), @([seg[1] doubleValue])]];
        }
        track.skipSegments = segments;
        if ([info[@"artworkURL"] isKindOfClass:[NSString class]]) track.artworkURLString = info[@"artworkURL"];
        if ([info[@"artwork"] boolValue] || track.artworkURLString.length) [ARArtwork forgetArtworkForTrack:track];
        [[ARStore shared] updateTrack:track];
        if (self.currentTrack != track) return;
        self->_metadataVersion++;
        [ARArtwork loadArtworkForTrack:track size:300 completion:^(UIImage *image) {
            if (self.currentTrack != track || !image) return;
            self->_artwork = image;
            [self updateNowPlayingInfo];
        }];
        [self skipSegmentIfNeeded];
        [self changed];
    }];
}

- (void)skipSegmentIfNeeded {
    ARTrack *track = self.currentTrack;
    if (!track.skipSegments.count || !self.skipsNonMusic || _item.status != AVPlayerItemStatusReadyToPlay) return;
    NSTimeInterval t = self.currentTime, duration = self.duration;
    for (NSArray<NSNumber *> *seg in track.skipSegments) {
        NSTimeInterval start = seg[0].doubleValue, end = seg[1].doubleValue;
        if (t < start || t >= end - 0.5) continue;
        if (duration > 0 && end >= duration - 1) {
            [self trackFinished]; // the rest of the video isn't music
        } else {
            [_player seekToTime:CMTimeMakeWithSeconds(end, 600) toleranceBefore:kCMTimeZero toleranceAfter:kCMTimeZero];
        }
        return;
    }
}

- (void)itemDidEnd:(NSNotification *)note {
    if (note.object != _item) return;
    dispatch_async(dispatch_get_main_queue(), ^{ [self trackFinished]; });
}

- (void)trackFinished {
    if (_repeatMode == ARRepeatOne) {
        [_player seekToTime:kCMTimeZero];
        [_player play];
    } else if (_index + 1 < (NSInteger)_queue.count) {
        _index++;
        [self loadCurrentAndPlay:YES];
    } else if (_repeatMode == ARRepeatAll && _queue.count) {
        _index = 0;
        [self loadCurrentAndPlay:YES];
    } else {
        _wantsPlay = NO;
        [_player pause];
        [_player seekToTime:kCMTimeZero];
        [self changed];
    }
}

- (void)itemFailed:(NSNotification *)note {
    if (note.object != _item) return;
    NSError *error = note.userInfo[AVPlayerItemFailedToPlayToEndTimeErrorKey];
    dispatch_async(dispatch_get_main_queue(), ^{
        if (self.currentTime < 1 && [self retryThroughProxy]) return;
        self->_errorMessage = error.localizedDescription ?: @"Playback stopped unexpectedly.";
        self->_wantsPlay = NO;
        [self changed];
    });
}

#pragma mark - Transport

- (void)play {
    if (!self.currentTrack) return;
    if (!_item || _item.status == AVPlayerItemStatusFailed || _errorMessage) {
        if (_resolving) { _wantsPlay = YES; [self changed]; return; }
        // Retry: the proxy may have been down or the stream URL may have expired.
        _pendingSeek = self.currentTime;
        _pendingSeekTrack = self.currentTrack;
        [self loadCurrentAndPlay:YES];
        return;
    }
    _wantsPlay = YES;
    [[AVAudioSession sharedInstance] setActive:YES error:nil];
    [_player play];
    [self changed];
}

- (void)pause {
    _wantsPlay = NO;
    [_player pause];
    [self changed];
}

- (void)togglePlayPause {
    if (_wantsPlay) [self pause];
    else [self play];
}

- (void)next {
    if (_queue.count == 0) return;
    if (_index + 1 < (NSInteger)_queue.count) _index++;
    else if (_repeatMode != ARRepeatOff) _index = 0;
    else return;
    [self loadCurrentAndPlay:_wantsPlay || !_item];
}

- (void)previous {
    if (_queue.count == 0) return;
    if (self.currentTime > 3 || (_index == 0 && _repeatMode == ARRepeatOff)) {
        [self seekTo:0];
        return;
    }
    _index = _index > 0 ? _index - 1 : (NSInteger)_queue.count - 1;
    [self loadCurrentAndPlay:_wantsPlay];
}

- (void)seekTo:(NSTimeInterval)time {
    [_player seekToTime:CMTimeMakeWithSeconds(MAX(0, time), 600) toleranceBefore:kCMTimeZero toleranceAfter:kCMTimeZero completionHandler:^(BOOL finished) {
        dispatch_async(dispatch_get_main_queue(), ^{ [self changed]; });
    }];
}

#pragma mark - System integration

- (void)interruption:(NSNotification *)note {
    AVAudioSessionInterruptionType type = [note.userInfo[AVAudioSessionInterruptionTypeKey] unsignedIntegerValue];
    dispatch_async(dispatch_get_main_queue(), ^{
        if (type == AVAudioSessionInterruptionTypeBegan) {
            self->_resumeAfterInterruption = self->_wantsPlay;
            self->_wantsPlay = NO;
            [self changed];
        } else {
            AVAudioSessionInterruptionOptions options = [note.userInfo[AVAudioSessionInterruptionOptionKey] unsignedIntegerValue];
            if (self->_resumeAfterInterruption && (options & AVAudioSessionInterruptionOptionShouldResume)) [self play];
            self->_resumeAfterInterruption = NO;
        }
    });
}

- (void)routeChange:(NSNotification *)note {
    AVAudioSessionRouteChangeReason reason = [note.userInfo[AVAudioSessionRouteChangeReasonKey] unsignedIntegerValue];
    if (reason != AVAudioSessionRouteChangeReasonOldDeviceUnavailable) return;
    // Headphones were unplugged. Don't blast music out of the speaker.
    dispatch_async(dispatch_get_main_queue(), ^{ [self pause]; });
}

- (void)updateNowPlayingInfo {
    ARTrack *track = self.currentTrack;
    MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
    if (!track) {
        center.nowPlayingInfo = nil;
        return;
    }
    NSMutableDictionary *info = [NSMutableDictionary dictionary];
    info[MPMediaItemPropertyTitle] = track.title ?: @"";
    info[MPMediaItemPropertyArtist] = track.artist ?: @"";
    if (track.album.length) info[MPMediaItemPropertyAlbumTitle] = track.album;
    info[MPMediaItemPropertyPlaybackDuration] = @(self.duration);
    info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @(self.currentTime);
    info[MPNowPlayingInfoPropertyPlaybackRate] = @(_player.rate > 0 ? 1.0 : 0.0);
    info[MPNowPlayingInfoPropertyPlaybackQueueIndex] = @(_index);
    info[MPNowPlayingInfoPropertyPlaybackQueueCount] = @(_queue.count);
    if (_artwork) info[MPMediaItemPropertyArtwork] = [[MPMediaItemArtwork alloc] initWithImage:_artwork];
    center.nowPlayingInfo = info;
}

- (void)setUpRemoteCommands {
    MPRemoteCommandCenter *cc = [MPRemoteCommandCenter sharedCommandCenter];
    [cc.playCommand addTarget:self action:@selector(remotePlay:)];
    [cc.pauseCommand addTarget:self action:@selector(remotePause:)];
    [cc.togglePlayPauseCommand addTarget:self action:@selector(remoteToggle:)];
    [cc.nextTrackCommand addTarget:self action:@selector(remoteNext:)];
    [cc.previousTrackCommand addTarget:self action:@selector(remotePrevious:)];
    if ([cc respondsToSelector:@selector(changePlaybackPositionCommand)]) { // iOS 9.1+
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunguarded-availability"
        [cc.changePlaybackPositionCommand addTarget:self action:@selector(remoteSeek:)];
#pragma clang diagnostic pop
    }
}

- (MPRemoteCommandHandlerStatus)remotePlay:(MPRemoteCommandEvent *)e { [self play]; return MPRemoteCommandHandlerStatusSuccess; }
- (MPRemoteCommandHandlerStatus)remotePause:(MPRemoteCommandEvent *)e { [self pause]; return MPRemoteCommandHandlerStatusSuccess; }
- (MPRemoteCommandHandlerStatus)remoteToggle:(MPRemoteCommandEvent *)e { [self togglePlayPause]; return MPRemoteCommandHandlerStatusSuccess; }
- (MPRemoteCommandHandlerStatus)remoteNext:(MPRemoteCommandEvent *)e { [self next]; return MPRemoteCommandHandlerStatusSuccess; }
- (MPRemoteCommandHandlerStatus)remotePrevious:(MPRemoteCommandEvent *)e { [self previous]; return MPRemoteCommandHandlerStatusSuccess; }

- (MPRemoteCommandHandlerStatus)remoteSeek:(MPRemoteCommandEvent *)e {
    [self seekTo:[[e valueForKey:@"positionTime"] doubleValue]];
    return MPRemoteCommandHandlerStatusSuccess;
}

@end
