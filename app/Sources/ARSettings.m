#import "ARSettings.h"

NSString *const ARSettingsDidChangeNotification = @"ARSettingsDidChangeNotification";

static NSString *const ARSettingsKey = @"ARSettings";
static NSString *const ARLegacySkipKey = @"ARSkipNonMusic";
static NSString *const ARPendingKey = @"ARSettingsPendingUpload";

/// String, number or array, like the default. Concrete classes differ (NSTaggedPointerString, __NSCFNumber...).
static BOOL ARSameKind(id a, id b) {
    for (Class cls in @[[NSString class], [NSNumber class], [NSArray class]]) {
        if ([b isKindOfClass:cls]) return [a isKindOfClass:cls];
    }
    return NO;
}

@implementation ARSettings {
    NSMutableDictionary *_values;
}

+ (instancetype)shared {
    static ARSettings *settings;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ settings = [ARSettings new]; });
    return settings;
}

+ (NSArray *)defaultDirectClients {
    // Same as DIRECT_CLIENTS in aria_proxy.py. The website can replace these when YouTube retires them.
    return @[
        @{@"name": @"ANDROID_VR", @"id": @28, @"version": @"1.65.10",
          @"userAgent": @"com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
          @"extra": @{@"deviceMake": @"Oculus", @"deviceModel": @"Quest 3", @"androidSdkVersion": @32, @"osName": @"Android", @"osVersion": @"12L"}},
        @{@"name": @"IOS", @"id": @5, @"version": @"20.10.4",
          @"userAgent": @"com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
          @"extra": @{@"deviceMake": @"Apple", @"deviceModel": @"iPhone16,2", @"osName": @"iPhone", @"osVersion": @"18.3.2.22D82"}},
    ];
}

+ (NSDictionary *)defaults {
    return @{@"mode": @"auto", @"accent": @"#FF3B6B", @"theme": @"dark", @"nowPlayingBackground": @"blur",
             @"artworkStyle": @"rounded", @"startTab": @"library", @"librarySort": @"title",
             @"searchResults": @25, @"skipNonMusic": @YES, @"directClients": [self defaultDirectClients]};
}

+ (NSSet *)appearanceKeys {
    return [NSSet setWithArray:@[@"accent", @"theme", @"nowPlayingBackground", @"artworkStyle", @"librarySort"]];
}

- (instancetype)init {
    if ((self = [super init])) {
        NSUserDefaults *d = [NSUserDefaults standardUserDefaults];
        _values = [NSMutableDictionary dictionaryWithDictionary:[ARSettings defaults]];
        NSDictionary *saved = [d dictionaryForKey:ARSettingsKey];
        if (saved) [_values addEntriesFromDictionary:saved];
        else if ([d objectForKey:ARLegacySkipKey]) _values[@"skipNonMusic"] = @([d boolForKey:ARLegacySkipKey]); // from 1.1
    }
    return self;
}

- (id)value:(NSString *)key ofClass:(Class)cls {
    id v = _values[key];
    return [v isKindOfClass:cls] ? v : [ARSettings defaults][key];
}

- (void)set:(id)value forKey:(NSString *)key {
    if ([_values[key] isEqual:value]) return;
    _values[key] = value;
    NSMutableDictionary *pending = [self.pendingUpload mutableCopy];
    pending[key] = value;
    [[NSUserDefaults standardUserDefaults] setObject:pending forKey:ARPendingKey];
    [self persistAndNotify:[[ARSettings appearanceKeys] containsObject:key]];
}

- (void)persistAndNotify:(BOOL)appearance {
    [[NSUserDefaults standardUserDefaults] setObject:_values forKey:ARSettingsKey];
    [[NSNotificationCenter defaultCenter] postNotificationName:ARSettingsDidChangeNotification object:self userInfo:@{@"appearance": @(appearance)}];
}

- (BOOL)applyRemoteSettings:(NSDictionary *)settings {
    if (![settings isKindOfClass:[NSDictionary class]]) return NO;
    BOOL changed = NO, appearance = NO;
    for (NSString *key in [ARSettings defaults]) {
        id value = settings[key];
        if (!value || [value isKindOfClass:[NSNull class]] || [_values[key] isEqual:value]) continue;
        if (self.pendingUpload[key]) continue; // changed here; the server hears about it first
        if (!ARSameKind(value, [ARSettings defaults][key])) continue;
        _values[key] = value;
        changed = YES;
        if ([[ARSettings appearanceKeys] containsObject:key]) appearance = YES;
    }
    if (changed) [self persistAndNotify:appearance];
    return changed;
}

- (NSDictionary *)pendingUpload {
    return [[NSUserDefaults standardUserDefaults] dictionaryForKey:ARPendingKey] ?: [NSDictionary dictionary];
}

- (void)uploadFinished:(NSDictionary *)sent {
    NSMutableDictionary *pending = [self.pendingUpload mutableCopy];
    for (NSString *key in sent) {
        if ([pending[key] isEqual:sent[key]]) [pending removeObjectForKey:key]; // unless it changed again meanwhile
    }
    [[NSUserDefaults standardUserDefaults] setObject:pending forKey:ARPendingKey];
}

#pragma mark - Typed access

- (ARStreamMode)mode {
    NSString *m = [self value:@"mode" ofClass:[NSString class]];
    if ([m isEqualToString:@"direct"]) return ARStreamModeDirect;
    if ([m isEqualToString:@"proxy"]) return ARStreamModeProxy;
    return ARStreamModeAuto;
}

- (void)setMode:(ARStreamMode)mode {
    [self set:mode == ARStreamModeDirect ? @"direct" : mode == ARStreamModeProxy ? @"proxy" : @"auto" forKey:@"mode"];
}

- (UIColor *)accentColor {
    NSString *hex = [self value:@"accent" ofClass:[NSString class]];
    unsigned int rgb = 0;
    if (hex.length == 7 && [[NSScanner scannerWithString:[hex substringFromIndex:1]] scanHexInt:&rgb]) {
        return [UIColor colorWithRed:((rgb >> 16) & 0xFF) / 255.0 green:((rgb >> 8) & 0xFF) / 255.0 blue:(rgb & 0xFF) / 255.0 alpha:1];
    }
    return [UIColor colorWithRed:1 green:0.231 blue:0.42 alpha:1];
}

- (NSString *)theme { return [self value:@"theme" ofClass:[NSString class]]; }
- (BOOL)solidNowPlayingBackground { return [[self value:@"nowPlayingBackground" ofClass:[NSString class]] isEqualToString:@"solid"]; }
- (BOOL)squareArtwork { return [[self value:@"artworkStyle" ofClass:[NSString class]] isEqualToString:@"square"]; }
- (BOOL)startsOnStream { return [[self value:@"startTab" ofClass:[NSString class]] isEqualToString:@"stream"]; }
- (NSString *)librarySort { return [self value:@"librarySort" ofClass:[NSString class]]; }

- (NSInteger)searchResults {
    return MAX(5, MIN(50, [[self value:@"searchResults" ofClass:[NSNumber class]] integerValue]));
}

- (BOOL)skipNonMusic { return [[self value:@"skipNonMusic" ofClass:[NSNumber class]] boolValue]; }
- (void)setSkipNonMusic:(BOOL)skip { [self set:@(skip) forKey:@"skipNonMusic"]; }

- (NSArray<NSDictionary *> *)directClients {
    NSArray *clients = [self value:@"directClients" ofClass:[NSArray class]];
    NSMutableArray *valid = [NSMutableArray array];
    for (NSDictionary *c in clients) {
        if ([c isKindOfClass:[NSDictionary class]] && [c[@"name"] isKindOfClass:[NSString class]] &&
            [c[@"version"] isKindOfClass:[NSString class]] && [c[@"userAgent"] isKindOfClass:[NSString class]]) [valid addObject:c];
    }
    return valid.count ? valid : [ARSettings defaultDirectClients];
}

@end
