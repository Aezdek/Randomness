#import "ARAlbumViewController.h"
#import "ARTrackCell.h"
#import "ARPlayer.h"
#import "ARTrack.h"
#import "ARTheme.h"
#import "ARIcons.h"
#import "ARArtwork.h"

@implementation ARAlbumViewController {
    MPMediaItemCollection *_collection;
    NSArray<ARTrack *> *_tracks;
}

- (instancetype)initWithCollection:(MPMediaItemCollection *)collection {
    if ((self = [super init])) {
        _collection = collection;
        NSMutableArray *tracks = [NSMutableArray array];
        for (MPMediaItem *item in collection.items) [tracks addObject:[ARTrack trackWithMediaItem:item]];
        _tracks = tracks;
    }
    return self;
}

- (UIButton *)pillButton:(NSString *)title icon:(ARIcon)icon action:(SEL)action {
    UIButton *b = [UIButton buttonWithType:UIButtonTypeCustom];
    [b setTitle:title forState:UIControlStateNormal];
    [b setTitleColor:[ARTheme accent] forState:UIControlStateNormal];
    [b setTitleColor:[[ARTheme accent] colorWithAlphaComponent:0.5] forState:UIControlStateHighlighted];
    b.titleLabel.font = [ARTheme fontOfSize:16 weight:UIFontWeightSemibold];
    [b setImage:[ARIcons icon:icon size:16 color:[ARTheme accent]] forState:UIControlStateNormal];
    b.imageEdgeInsets = UIEdgeInsetsMake(0, -6, 0, 6);
    [b setBackgroundImage:[ARTheme pillImageWithColor:[ARTheme surfaceHighlight] height:44] forState:UIControlStateNormal];
    [b addTarget:self action:action forControlEvents:UIControlEventTouchUpInside];
    return b;
}

- (void)viewDidLoad {
    MPMediaItem *item = _collection.representativeItem;
    CGFloat width = [UIScreen mainScreen].bounds.size.width;
    UIView *header = [[UIView alloc] initWithFrame:CGRectMake(0, 0, width, 320)];

    CGFloat art = 200;
    UIView *card = [[UIView alloc] initWithFrame:CGRectMake((width - art) / 2, 12, art, art)];
    card.layer.shadowColor = [UIColor blackColor].CGColor;
    card.layer.shadowOpacity = 0.45;
    card.layer.shadowRadius = 14;
    card.layer.shadowOffset = CGSizeMake(0, 8);
    CGFloat radius = [ARTheme artworkCornerRadius:8];
    card.layer.shadowPath = [UIBezierPath bezierPathWithRoundedRect:card.bounds cornerRadius:radius].CGPath;
    UIImageView *artwork = [[UIImageView alloc] initWithFrame:card.bounds];
    artwork.layer.cornerRadius = radius;
    artwork.clipsToBounds = YES;
    artwork.contentMode = UIViewContentModeScaleAspectFill;
    artwork.image = [ARTheme placeholderArtworkOfSize:art];
    [card addSubview:artwork];
    [header addSubview:card];
    if (_tracks.count) {
        [ARArtwork loadArtworkForTrack:_tracks.firstObject size:art completion:^(UIImage *image) {
            if (image) artwork.image = image;
        }];
    }

    UILabel *title = [[UILabel alloc] initWithFrame:CGRectMake(16, 224, width - 32, 26)];
    title.text = item.albumTitle.length ? item.albumTitle : @"Unknown Album";
    title.font = [ARTheme fontOfSize:21 weight:UIFontWeightBold];
    title.textColor = [ARTheme textPrimary];
    title.textAlignment = NSTextAlignmentCenter;
    [header addSubview:title];

    UILabel *artist = [[UILabel alloc] initWithFrame:CGRectMake(16, 250, width - 32, 22)];
    artist.text = item.albumArtist ?: item.artist ?: @"Unknown Artist";
    artist.font = [ARTheme fontOfSize:17 weight:UIFontWeightRegular];
    artist.textColor = [ARTheme accent];
    artist.textAlignment = NSTextAlignmentCenter;
    [header addSubview:artist];

    CGFloat bw = (width - 16 * 2 - 12) / 2;
    UIButton *play = [self pillButton:@"Play" icon:ARIconPlay action:@selector(playAll)];
    play.frame = CGRectMake(16, 282, bw, 44);
    UIButton *shuffle = [self pillButton:@"Shuffle" icon:ARIconShuffle action:@selector(shuffleAll)];
    shuffle.frame = CGRectMake(16 + bw + 12, 282, bw, 44);
    [header addSubview:play];
    [header addSubview:shuffle];
    header.frame = CGRectMake(0, 0, width, 340);
    self.headerAccessoryView = header;

    [super viewDidLoad];
    self.title = title.text;
    self.tableView.separatorInset = UIEdgeInsetsMake(0, 16, 0, 0);
}

- (void)playAll {
    [ARPlayer shared].shuffle = NO;
    [[ARPlayer shared] playTracks:_tracks startIndex:0];
}

- (void)shuffleAll {
    [ARPlayer shared].shuffle = YES;
    [[ARPlayer shared] playTracks:_tracks startIndex:-1];
}

- (ARTrack *)trackAtIndexPath:(NSIndexPath *)ip {
    return ip.row < (NSInteger)_tracks.count ? _tracks[ip.row] : nil;
}

- (NSInteger)tableView:(UITableView *)tableView numberOfRowsInSection:(NSInteger)section {
    return _tracks.count;
}

- (CGFloat)tableView:(UITableView *)tableView heightForRowAtIndexPath:(NSIndexPath *)indexPath { return 52; }

- (UITableViewCell *)tableView:(UITableView *)tableView cellForRowAtIndexPath:(NSIndexPath *)indexPath {
    ARTrackCell *cell = [tableView dequeueReusableCellWithIdentifier:@"track" forIndexPath:indexPath];
    ARTrack *track = _tracks[indexPath.row];
    [cell configureWithTrack:track];
    cell.showsArtwork = NO;
    NSUInteger number = track.mediaItem.albumTrackNumber;
    NSString *prefix = number ? [NSString stringWithFormat:@"%lu   ", (unsigned long)number] : @"";
    cell.titleLabel.text = [prefix stringByAppendingString:track.title];
    cell.subtitleLabel.text = nil;
    return cell;
}

- (void)tableView:(UITableView *)tableView didSelectRowAtIndexPath:(NSIndexPath *)indexPath {
    [tableView deselectRowAtIndexPath:indexPath animated:YES];
    [[ARPlayer shared] playTracks:_tracks startIndex:indexPath.row];
}

@end
