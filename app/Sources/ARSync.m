#import "ARSync.h"
#import "ARProxyClient.h"
#import "ARSettings.h"
#import "ARStore.h"
#import "ARTrack.h"
#import <UIKit/UIKit.h>

@implementation ARSync {
    NSTimer *_timer;
    BOOL _syncing;
    BOOL _pushPending;
}

+ (instancetype)shared {
    static ARSync *sync;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ sync = [ARSync new]; });
    return sync;
}

- (void)start {
    NSNotificationCenter *nc = [NSNotificationCenter defaultCenter];
    [nc addObserver:self selector:@selector(becameActive) name:UIApplicationDidBecomeActiveNotification object:nil];
    [nc addObserver:self selector:@selector(resignedActive) name:UIApplicationWillResignActiveNotification object:nil];
    [nc addObserver:self selector:@selector(savedChangedHere) name:ARStoreSavedChangedLocallyNotification object:nil];
    [nc addObserver:self selector:@selector(settingsChanged) name:ARSettingsDidChangeNotification object:nil];
    [self becameActive];
}

- (void)becameActive {
    [self syncNow];
    [_timer invalidate];
    _timer = [NSTimer scheduledTimerWithTimeInterval:30 target:self selector:@selector(syncNow) userInfo:nil repeats:YES];
}

- (void)resignedActive {
    [_timer invalidate];
    _timer = nil;
}

- (void)settingsChanged {
    if ([ARSettings shared].pendingUpload.count) [self syncNow];
}

- (void)savedChangedHere {
    _pushPending = YES;
    [self syncNow];
}

- (void)syncNow {
    ARProxyClient *proxy = [ARProxyClient shared];
    if (!proxy.configured || _syncing) return;
    _syncing = YES;
    NSDictionary *pending = [ARSettings shared].pendingUpload;
    if (pending.count) {
        // Settings changed on the iPod go up first, so the server's older values don't undo them.
        [proxy uploadSettings:pending completion:^(NSDictionary *config, NSError *error) {
            if (config) [[ARSettings shared] uploadFinished:pending];
            self->_syncing = NO;
            if (config) [self syncNow];
        }];
        return;
    }
    [proxy fetchConfig:^(NSDictionary *config, NSError *error) {
        if (!config) {
            self->_syncing = NO;
            return; // offline or an older server: nothing to do
        }
        [[ARSettings shared] applyRemoteSettings:config[@"settings"]];

        NSTimeInterval serverUpdated = [config[@"savedUpdated"] doubleValue];
        NSTimeInterval localUpdated = [ARStore shared].savedUpdated;
        if (localUpdated > serverUpdated + 0.5 || (self->_pushPending && localUpdated >= serverUpdated)) {
            NSArray *saved = [[ARStore shared].savedTracks valueForKey:@"dictionaryRepresentation"];
            [proxy uploadSaved:saved updated:localUpdated completion:^(NSDictionary *c, NSError *e) {
                if (c) self->_pushPending = NO;
                self->_syncing = NO;
            }];
            return;
        }
        if (serverUpdated > localUpdated + 0.5 && [config[@"saved"] isKindOfClass:[NSArray class]]) {
            [[ARStore shared] replaceSaved:config[@"saved"] updated:serverUpdated];
        }
        self->_pushPending = NO;
        self->_syncing = NO;
    }];
}

@end
