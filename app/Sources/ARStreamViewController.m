#import "ARStreamViewController.h"
#import "ARTrackCell.h"
#import "ARTrackActions.h"
#import "ARProxyClient.h"
#import "ARPlayer.h"
#import "ARStore.h"
#import "ARTrack.h"
#import "ARTheme.h"
#import "ARIcons.h"
#import "ARYouTube.h"
#import "ARSettings.h"

@interface ARStreamViewController () <UISearchBarDelegate>
@end

@implementation ARStreamViewController {
    UISearchBar *_searchBar;
    UIActivityIndicatorView *_spinner;
    BOOL _searching;
    NSUInteger _searchID; // answers to older searches are ignored
    NSArray<ARTrack *> *_results;   // nil when not showing search results
    NSArray<NSString *> *_sectionTitles;
    NSArray<NSArray<ARTrack *> *> *_sections;
    NSString *_lastError;
}

- (instancetype)init {
    if ((self = [super init])) {
        self.tabBarItem = [[UITabBarItem alloc] initWithTitle:@"Stream" image:[ARIcons icon:ARIconStream size:25] tag:1];
    }
    return self;
}

- (void)viewDidLoad {
    UIView *accessory = [[UIView alloc] initWithFrame:CGRectMake(0, 0, 320, 48)];
    _searchBar = [[UISearchBar alloc] initWithFrame:CGRectMake(8, 0, 304, 44)];
    _searchBar.autoresizingMask = UIViewAutoresizingFlexibleWidth;
    _searchBar.searchBarStyle = UISearchBarStyleMinimal;
    _searchBar.barStyle = UIBarStyleBlack;
    _searchBar.keyboardAppearance = UIKeyboardAppearanceDark;
    _searchBar.placeholder = @"Songs, artists, videos";
    _searchBar.tintColor = [ARTheme accent];
    _searchBar.returnKeyType = UIReturnKeySearch;
    _searchBar.delegate = self;
    [accessory addSubview:_searchBar];
    self.headerAccessoryView = accessory;

    [super viewDidLoad];
    self.largeTitle = @"Stream";

    _spinner = [[UIActivityIndicatorView alloc] initWithActivityIndicatorStyle:UIActivityIndicatorViewStyleWhite];
    _spinner.hidesWhenStopped = YES;
    self.navigationItem.rightBarButtonItem = [[UIBarButtonItem alloc] initWithCustomView:_spinner];

    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(reload) name:ARStoreDidChangeNotification object:nil];
}

- (void)viewWillAppear:(BOOL)animated {
    [super viewWillAppear:animated];
    [self reload]; // the proxy may have been set up in Settings
}

- (void)reload {
    if (_results) {
        _sectionTitles = @[@""];
        _sections = @[_results];
    } else {
        NSMutableArray *titles = [NSMutableArray array], *sections = [NSMutableArray array];
        ARStore *store = [ARStore shared];
        if (store.savedTracks.count) {
            [titles addObject:@"Saved"];
            [sections addObject:store.savedTracks];
        }
        if (store.recentTracks.count) {
            [titles addObject:@"Recently Played"];
            [sections addObject:store.recentTracks];
        }
        _sectionTitles = titles;
        _sections = sections;
    }
    [self.tableView reloadData];
    [self updateEmptyState];
}

- (void)updateEmptyState {
    __weak typeof(self) weakSelf = self;
    if ([ARSettings shared].mode == ARStreamModeProxy && ![ARProxyClient shared].configured) {
        [self showEmptyStateWithTitle:@"Connect Your Proxy"
                              message:@"Streaming is set to Proxy, so Aria needs aria_proxy.py running on a computer on your Wi-Fi. Add its address in Settings, or switch Streaming to Auto."
                          buttonTitle:@"Open Settings"
                               action:^{ if (weakSelf.onOpenSettings) weakSelf.onOpenSettings(); }];
    } else if (_lastError && _results.count == 0) {
        [self showEmptyStateWithTitle:@"Couldn't Search" message:_lastError buttonTitle:@"Try Again" action:^{
            [weakSelf runSearch];
        }];
    } else if (_results && _results.count == 0 && !_searching) {
        [self showEmptyStateWithTitle:@"No Results" message:@"Try different words." buttonTitle:nil action:nil];
    } else if (!_results && _sections.count == 0) {
        [self showEmptyStateWithTitle:@"Stream Anything"
                              message:@"Search YouTube for a song. Tap the heart in Now Playing to keep it here."
                          buttonTitle:nil
                               action:nil];
    } else {
        [self hideEmptyState];
    }
}

#pragma mark - Search

- (void)runSearch {
    NSString *q = [_searchBar.text stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
    if (!q.length) return;
    _lastError = nil;
    [_spinner startAnimating];
    [self hideEmptyState];
    __weak typeof(self) weakSelf = self;
    _searching = YES;
    NSUInteger searchID = ++_searchID;
    [ARYouTube search:q completion:^(NSArray<ARTrack *> *results, NSError *error) {
        typeof(self) strongSelf = weakSelf;
        if (!strongSelf || searchID != strongSelf->_searchID) return;
        strongSelf->_searching = NO;
        [strongSelf->_spinner stopAnimating];
        strongSelf->_lastError = error.localizedDescription;
        strongSelf->_results = results ?: [NSArray array];
        [strongSelf reload];
        [strongSelf.tableView setContentOffset:CGPointMake(0, -strongSelf.tableView.contentInset.top) animated:NO];
    }];
}

- (void)searchBarSearchButtonClicked:(UISearchBar *)searchBar {
    [searchBar resignFirstResponder];
    [self runSearch];
}

- (void)searchBarTextDidBeginEditing:(UISearchBar *)searchBar { [searchBar setShowsCancelButton:YES animated:YES]; }

- (void)searchBar:(UISearchBar *)searchBar textDidChange:(NSString *)searchText {
    if (searchText.length == 0) [self clearSearch];
}

- (void)searchBarCancelButtonClicked:(UISearchBar *)searchBar {
    searchBar.text = @"";
    [searchBar resignFirstResponder];
    [searchBar setShowsCancelButton:NO animated:YES];
    [self clearSearch];
}

- (void)clearSearch {
    _searching = NO;
    _searchID++;
    [_spinner stopAnimating];
    _results = nil;
    _lastError = nil;
    [self reload];
}

#pragma mark - Table

- (ARTrack *)trackAtIndexPath:(NSIndexPath *)ip {
    if (ip.section >= (NSInteger)_sections.count) return nil;
    NSArray *rows = _sections[ip.section];
    return ip.row < (NSInteger)rows.count ? rows[ip.row] : nil;
}

- (NSInteger)numberOfSectionsInTableView:(UITableView *)tableView { return _sections.count; }

- (NSInteger)tableView:(UITableView *)tableView numberOfRowsInSection:(NSInteger)section {
    return _sections[section].count;
}

- (CGFloat)tableView:(UITableView *)tableView heightForHeaderInSection:(NSInteger)section {
    return _sectionTitles[section].length ? 44 : 0;
}

- (UIView *)tableView:(UITableView *)tableView viewForHeaderInSection:(NSInteger)section {
    return _sectionTitles[section].length ? [self sectionHeaderWithTitle:_sectionTitles[section]] : nil;
}

- (UITableViewCell *)tableView:(UITableView *)tableView cellForRowAtIndexPath:(NSIndexPath *)indexPath {
    ARTrackCell *cell = [tableView dequeueReusableCellWithIdentifier:@"track" forIndexPath:indexPath];
    ARTrack *track = [self trackAtIndexPath:indexPath];
    __weak typeof(self) weakSelf = self;
    cell.onMore = ^(ARTrackCell *c) {
        [ARTrackActions presentForTrack:track from:weakSelf sourceView:c];
    };
    [cell configureWithTrack:track];
    return cell;
}

- (void)tableView:(UITableView *)tableView didSelectRowAtIndexPath:(NSIndexPath *)indexPath {
    [tableView deselectRowAtIndexPath:indexPath animated:YES];
    [_searchBar resignFirstResponder];
    ARTrack *track = [self trackAtIndexPath:indexPath];
    if ([track isSameTrack:[ARPlayer shared].currentTrack]) {
        [[ARPlayer shared] play];
        return;
    }
    [[ARPlayer shared] playTracks:_sections[indexPath.section] startIndex:indexPath.row];
}

@end
