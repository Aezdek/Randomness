#import <UIKit/UIKit.h>

/// Posted when settings change. userInfo[@"appearance"] is YES when the look changed.
extern NSString *const ARSettingsDidChangeNotification;

typedef NS_ENUM(NSInteger, ARStreamMode) {
    ARStreamModeAuto,   // the iPod tries YouTube itself, then the proxy
    ARStreamModeDirect,
    ARStreamModeProxy,
};

/// Settings edited on the server's website (or in the app) and kept in NSUserDefaults.
@interface ARSettings : NSObject

+ (instancetype)shared;

@property (nonatomic) ARStreamMode mode;
@property (nonatomic, readonly) UIColor *accentColor;
@property (nonatomic, readonly) NSString *theme;                  // dark | black | midnight
@property (nonatomic, readonly) BOOL solidNowPlayingBackground;
@property (nonatomic, readonly) BOOL squareArtwork;
@property (nonatomic, readonly) BOOL startsOnStream;
@property (nonatomic, readonly) NSString *librarySort;            // title | artist | album
@property (nonatomic, readonly) NSInteger searchResults;
@property (nonatomic) BOOL skipNonMusic;
/// YouTube app profiles for direct mode: [{name, id, version, userAgent, extra}]
@property (nonatomic, readonly) NSArray<NSDictionary *> *directClients;

/// Applies settings from the server, except ones changed here that the server hasn't got yet.
/// Returns YES when something changed.
- (BOOL)applyRemoteSettings:(NSDictionary *)settings;
/// Settings changed on this iPod and not yet sent to the server.
@property (nonatomic, readonly) NSDictionary *pendingUpload;
- (void)uploadFinished:(NSDictionary *)sent;

@end
