#import "ARLibraryViewController.h"
#import "ARAlbumViewController.h"
#import "ARTrackCell.h"
#import "ARPlayer.h"
#import "ARTrack.h"
#import "ARTheme.h"
#import "ARIcons.h"
#import "ARSettings.h"
#import <MediaPlayer/MediaPlayer.h>

typedef NS_ENUM(NSInteger, ARLibraryMode) { ARLibrarySongs, ARLibraryAlbums };

@interface ARLibraryViewController () <UISearchBarDelegate>
@end

@implementation ARLibraryViewController {
    UISegmentedControl *_segments;
    UISearchBar *_searchBar;
    NSArray<ARTrack *> *_songs;
    NSArray<MPMediaItemCollection *> *_albums;
    NSArray<ARTrack *> *_shownSongs;
    NSArray<MPMediaItemCollection *> *_shownAlbums;
    BOOL _loaded;
}

- (instancetype)init {
    if ((self = [super init])) {
        self.tabBarItem = [[UITabBarItem alloc] initWithTitle:@"Library" image:[ARIcons icon:ARIconNote size:25] tag:0];
    }
    return self;
}

- (void)viewDidLoad {
    UIView *accessory = [[UIView alloc] initWithFrame:CGRectMake(0, 0, 320, 88)];
    _segments = [[UISegmentedControl alloc] initWithItems:@[@"Songs", @"Albums"]];
    _segments.selectedSegmentIndex = 0;
    _segments.tintColor = [ARTheme accent];
    _segments.frame = CGRectMake(16, 0, 288, 29);
    _segments.autoresizingMask = UIViewAutoresizingFlexibleWidth;
    [_segments addTarget:self action:@selector(modeChanged) forControlEvents:UIControlEventValueChanged];
    [accessory addSubview:_segments];

    _searchBar = [[UISearchBar alloc] initWithFrame:CGRectMake(8, 38, 304, 44)];
    _searchBar.autoresizingMask = UIViewAutoresizingFlexibleWidth;
    _searchBar.searchBarStyle = UISearchBarStyleMinimal;
    _searchBar.barStyle = UIBarStyleBlack;
    _searchBar.keyboardAppearance = UIKeyboardAppearanceDark;
    _searchBar.placeholder = @"Search your library";
    _searchBar.tintColor = [ARTheme accent];
    _searchBar.delegate = self;
    [accessory addSubview:_searchBar];
    self.headerAccessoryView = accessory;

    [super viewDidLoad];
    self.largeTitle = @"Library";
    self.navigationItem.backBarButtonItem = [[UIBarButtonItem alloc] initWithTitle:@"Library" style:UIBarButtonItemStylePlain target:nil action:nil];

    MPMediaLibrary *library = [MPMediaLibrary defaultMediaLibrary];
    [library beginGeneratingLibraryChangeNotifications];
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(reloadLibrary) name:MPMediaLibraryDidChangeNotification object:library];
    [self reloadLibrary];
}

- (ARLibraryMode)mode { return _segments.selectedSegmentIndex; }

- (void)reloadLibrary {
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        MPMediaQuery *songsQuery = [MPMediaQuery songsQuery];
        [songsQuery addFilterPredicate:[MPMediaPropertyPredicate predicateWithValue:@NO forProperty:MPMediaItemPropertyIsCloudItem]];
        NSMutableArray *songs = [NSMutableArray array];
        for (MPMediaItem *item in songsQuery.items) [songs addObject:[ARTrack trackWithMediaItem:item]];

        MPMediaQuery *albumsQuery = [MPMediaQuery albumsQuery];
        [albumsQuery addFilterPredicate:[MPMediaPropertyPredicate predicateWithValue:@NO forProperty:MPMediaItemPropertyIsCloudItem]];
        NSArray *albums = albumsQuery.collections ?: [NSArray array];
        NSString *sort = [ARSettings shared].librarySort;
        NSArray *keys = [sort isEqualToString:@"artist"] ? @[@"artist", @"album", @"title"]
                      : [sort isEqualToString:@"album"] ? @[@"album", @"title"] : @[@"title", @"artist"];
        NSMutableArray *descriptors = [NSMutableArray array];
        for (NSString *k in keys) [descriptors addObject:[NSSortDescriptor sortDescriptorWithKey:k ascending:YES selector:@selector(localizedCaseInsensitiveCompare:)]];
        [songs sortUsingDescriptors:descriptors];
        dispatch_async(dispatch_get_main_queue(), ^{
            self->_songs = songs;
            self->_albums = albums;
            self->_loaded = YES;
            [self applyFilter];
        });
    });
}

- (void)modeChanged {
    [self applyFilter];
}

- (void)applyFilter {
    NSString *q = [_searchBar.text stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
    if (q.length) {
        NSPredicate *songMatch = [NSPredicate predicateWithFormat:@"title CONTAINS[cd] %@ OR artist CONTAINS[cd] %@ OR album CONTAINS[cd] %@", q, q, q];
        _shownSongs = [_songs filteredArrayUsingPredicate:songMatch];
        _shownAlbums = [_albums filteredArrayUsingPredicate:[NSPredicate predicateWithBlock:^BOOL(MPMediaItemCollection *c, NSDictionary *b) {
            MPMediaItem *item = c.representativeItem;
            return [item.albumTitle rangeOfString:q options:NSCaseInsensitiveSearch | NSDiacriticInsensitiveSearch].location != NSNotFound
                || [item.albumArtist ?: item.artist rangeOfString:q options:NSCaseInsensitiveSearch | NSDiacriticInsensitiveSearch].location != NSNotFound;
        }]];
    } else {
        _shownSongs = _songs;
        _shownAlbums = _albums;
    }
    [self.tableView reloadData];

    if (_loaded && _songs.count == 0) {
        [self showEmptyStateWithTitle:@"No Music Yet"
                              message:@"Sync songs to this iPod with iTunes, or stream anything from the Stream tab."
                          buttonTitle:nil
                               action:nil];
    } else {
        [self hideEmptyState];
    }
}

#pragma mark - Search

- (void)searchBar:(UISearchBar *)searchBar textDidChange:(NSString *)searchText { [self applyFilter]; }
- (void)searchBarTextDidBeginEditing:(UISearchBar *)searchBar { [searchBar setShowsCancelButton:YES animated:YES]; }
- (void)searchBarTextDidEndEditing:(UISearchBar *)searchBar { [searchBar setShowsCancelButton:searchBar.text.length > 0 animated:YES]; }
- (void)searchBarSearchButtonClicked:(UISearchBar *)searchBar { [searchBar resignFirstResponder]; }

- (void)searchBarCancelButtonClicked:(UISearchBar *)searchBar {
    searchBar.text = @"";
    [searchBar resignFirstResponder];
    [searchBar setShowsCancelButton:NO animated:YES];
    [self applyFilter];
}

#pragma mark - Table

- (BOOL)showsShuffleRow {
    return self.mode == ARLibrarySongs && _shownSongs.count > 1;
}

- (ARTrack *)trackAtIndexPath:(NSIndexPath *)ip {
    if (self.mode != ARLibrarySongs || ip.section != 1 || ip.row >= (NSInteger)_shownSongs.count) return nil;
    return _shownSongs[ip.row];
}

- (NSInteger)numberOfSectionsInTableView:(UITableView *)tableView { return 2; }

- (NSInteger)tableView:(UITableView *)tableView numberOfRowsInSection:(NSInteger)section {
    if (section == 0) return self.showsShuffleRow ? 1 : 0;
    return self.mode == ARLibrarySongs ? _shownSongs.count : _shownAlbums.count;
}

- (CGFloat)tableView:(UITableView *)tableView heightForRowAtIndexPath:(NSIndexPath *)indexPath {
    return indexPath.section == 0 ? 52 : [ARTrackCell height];
}

- (UITableViewCell *)tableView:(UITableView *)tableView cellForRowAtIndexPath:(NSIndexPath *)indexPath {
    if (indexPath.section == 0) {
        UITableViewCell *cell = [tableView dequeueReusableCellWithIdentifier:@"shuffle"];
        if (!cell) {
            cell = [[UITableViewCell alloc] initWithStyle:UITableViewCellStyleDefault reuseIdentifier:@"shuffle"];
            cell.backgroundColor = [ARTheme background];
            cell.selectedBackgroundView = [UIView new];
            cell.selectedBackgroundView.backgroundColor = [ARTheme surfaceHighlight];
            cell.imageView.image = [ARIcons icon:ARIconShuffle size:22];
            cell.imageView.tintColor = [ARTheme accent];
            cell.textLabel.font = [ARTheme fontOfSize:17 weight:UIFontWeightSemibold];
            cell.textLabel.textColor = [ARTheme accent];
        }
        cell.textLabel.text = [NSString stringWithFormat:@"Shuffle %lu Songs", (unsigned long)_shownSongs.count];
        return cell;
    }
    ARTrackCell *cell = [tableView dequeueReusableCellWithIdentifier:@"track" forIndexPath:indexPath];
    if (self.mode == ARLibrarySongs) {
        [cell configureWithTrack:_shownSongs[indexPath.row]];
    } else {
        MPMediaItemCollection *album = _shownAlbums[indexPath.row];
        MPMediaItem *item = album.representativeItem;
        cell.titleLabel.text = item.albumTitle.length ? item.albumTitle : @"Unknown Album";
        NSString *artist = item.albumArtist ?: item.artist ?: @"Unknown Artist";
        cell.subtitleLabel.text = [NSString stringWithFormat:@"%@ · %lu song%@", artist, (unsigned long)album.count, album.count == 1 ? @"" : @"s"];
        cell.detailLabel.text = nil;
        [cell loadArtworkForTrack:[ARTrack trackWithMediaItem:item]];
        cell.accessoryType = UITableViewCellAccessoryNone;
        [cell setNeedsLayout];
    }
    return cell;
}

- (void)tableView:(UITableView *)tableView didSelectRowAtIndexPath:(NSIndexPath *)indexPath {
    [tableView deselectRowAtIndexPath:indexPath animated:YES];
    [_searchBar resignFirstResponder];
    if (indexPath.section == 0) {
        [ARPlayer shared].shuffle = YES;
        [[ARPlayer shared] playTracks:_shownSongs startIndex:-1];
        return;
    }
    if (self.mode == ARLibrarySongs) {
        [[ARPlayer shared] playTracks:_shownSongs startIndex:indexPath.row];
    } else {
        ARAlbumViewController *vc = [[ARAlbumViewController alloc] initWithCollection:_shownAlbums[indexPath.row]];
        [self.navigationController pushViewController:vc animated:YES];
    }
}

@end
