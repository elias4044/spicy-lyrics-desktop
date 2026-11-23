#define NOMINMAX
#include <windows.h>
#include <mmsystem.h>
#include <filesystem>
#include <winrt/Windows.Data.Json.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Media.Control.h>
#include <winrt/Windows.Media.Core.h>
#include <winrt/Windows.Media.Playback.h>
#include <winrt/Windows.Storage.h>
#include <winrt/Windows.Storage.FileProperties.h>
#include <winrt/Windows.Storage.Streams.h>
#include <winrt/Windows.Security.Cryptography.h>
#include <chrono>
#include <iostream>
#include <map>
#include <string>

using namespace winrt;
using namespace Windows::Data::Json;
using namespace Windows::Media::Control;
using namespace Windows::Media::Playback;
using namespace Windows::Storage;
using namespace Windows::Storage::FileProperties;
using namespace Windows::Storage::Streams;
using namespace std::chrono;
using Session = GlobalSystemMediaTransportControlsSession;
using Manager = GlobalSystemMediaTransportControlsSessionManager;

void str(JsonObject const& o, wchar_t const* key, hstring const& value) {
    o.SetNamedValue(key, JsonValue::CreateStringValue(value));
}
void num(JsonObject const& o, wchar_t const* key, double value) {
    o.SetNamedValue(key, JsonValue::CreateNumberValue(value));
}
void flag(JsonObject const& o, wchar_t const* key, bool value) {
    o.SetNamedValue(key, JsonValue::CreateBooleanValue(value));
}

hstring artwork(IRandomAccessStream const& stream) {
    if (!stream || stream.Size() == 0 || stream.Size() > 2'000'000) return {};
    DataReader reader(stream.GetInputStreamAt(0));
    auto loaded = reader.LoadAsync(static_cast<uint32_t>(stream.Size())).get();
    if (loaded == 0) return {};
    return Windows::Security::Cryptography::CryptographicBuffer::EncodeToBase64String(reader.ReadBuffer(loaded));
}

struct Media {
    hstring title, artist, album, cover;
    long long duration = 0;
};

struct Cached {
    Media media;
    steady_clock::time_point checked{};
};

class Bridge {
    Manager manager{nullptr};
    MediaPlayer local{nullptr};
    bool legacyLocal = false;
    Media file;
    std::map<std::wstring, Cached> cache;

    Session select(hstring const& id) {
        if (!manager) return nullptr;
        if (id.empty()) {
            auto current = manager.GetCurrentSession();
            if (current && current.GetPlaybackInfo().PlaybackStatus() ==
                GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing) return current;
            for (auto const& session : manager.GetSessions()) {
                if (session.GetPlaybackInfo().PlaybackStatus() ==
                    GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing) return session;
            }
            return current;
        }
        for (auto const& session : manager.GetSessions()) {
            if (session.SourceAppUserModelId() == id) return session;
        }
        return nullptr;
    }

    void metadata(JsonObject const& result, Media const& media, bool cover) {
        str(result, L"title", media.title);
        str(result, L"artist", media.artist);
        str(result, L"album", media.album);
        if (cover) str(result, L"cover", media.cover);
    }

    std::wstring mci(std::wstring const& command) {
        wchar_t result[256]{};
        const auto error = mciSendStringW(command.c_str(), result, 256, nullptr);
        if (error) {
            wchar_t detail[256]{};
            mciGetErrorStringW(error, detail, 256);
            throw hresult_error(E_FAIL, detail);
        }
        return result;
    }

    JsonObject pollLegacy(bool cover) {
        JsonObject result;
        metadata(result, file, cover);
        num(result, L"position", std::stoll(mci(L"status spicy_audio position")));
        num(result, L"duration", file.duration);
        num(result, L"rate", 1);
        flag(result, L"playing", mci(L"status spicy_audio mode") == L"playing");
        flag(result, L"seek", true);
        flag(result, L"toggle", true);
        flag(result, L"clock", true);
        str(result, L"id", L"local");
        return result;
    }

    JsonObject poll(JsonObject const& request) {
        auto id = request.GetNamedString(L"session", L"");
        auto cover = request.GetNamedBoolean(L"cover", false);
        JsonObject result;
        if (id == L"local") {
            if (legacyLocal) return pollLegacy(cover);
            if (!local) return result;
            auto state = local.PlaybackSession();
            metadata(result, file, cover);
            num(result, L"position", duration_cast<milliseconds>(state.Position()).count());
            num(result, L"duration", duration_cast<milliseconds>(state.NaturalDuration()).count());
            num(result, L"rate", state.PlaybackRate());
            auto playing = state.PlaybackState() == MediaPlaybackState::Playing;
            flag(result, L"playing", playing);
            flag(result, L"seek", state.CanSeek());
            flag(result, L"toggle", true);
            flag(result, L"clock", true);
            str(result, L"id", L"local");
            return result;
        }
        auto session = select(id);
        if (!session) return result;
        auto app = session.SourceAppUserModelId();
        auto& entry = cache[std::wstring(app)];
        auto now = steady_clock::now();
        if (now - entry.checked > 850ms || cover) {
            auto props = session.TryGetMediaPropertiesAsync().get();
            entry.checked = now;
            if (props) {
                auto& data = entry.media;
                if (data.title != props.Title() || data.artist != props.Artist() || data.album != props.AlbumTitle()) {
                    data = {props.Title(), props.Artist(), props.AlbumTitle(), {}};
                }
                if (cover && data.cover.empty() && props.Thumbnail()) {
                    try { data.cover = artwork(props.Thumbnail().OpenReadAsync().get()); } catch (...) {}
                }
            }
        }
        metadata(result, entry.media, cover);
        str(result, L"id", app);
        auto timeline = session.GetTimelineProperties();
        auto playback = session.GetPlaybackInfo();
        auto control = playback.Controls();
        auto playing = playback.PlaybackStatus() == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing;
        auto rate = playback.PlaybackRate();
        auto speed = rate ? rate.Value() : 1.0;
        if (!(speed > 0 && speed <= 8)) speed = 1;
        auto start = duration_cast<milliseconds>(timeline.StartTime()).count();
        auto end = duration_cast<milliseconds>(timeline.EndTime()).count();
        auto position = duration_cast<milliseconds>(timeline.Position()).count() - start;
        auto updated = timeline.LastUpdatedTime();
        auto age = duration_cast<milliseconds>(winrt::clock::now() - updated).count();
        auto hasClock = updated.time_since_epoch().count() != 0 && (end > start || position > 0);
        num(result, L"position", (std::max)(0LL, position));
        num(result, L"duration", (std::max)(0LL, end - start));
        num(result, L"age", hasClock ? (std::max)(0LL, age) : 0);
        num(result, L"rate", speed);
        flag(result, L"clock", hasClock);
        flag(result, L"playing", playing);
        flag(result, L"seek", control.IsPlaybackPositionEnabled());
        flag(result, L"toggle", control.IsPlayPauseToggleEnabled() || control.IsPlayEnabled() || control.IsPauseEnabled());
        flag(result, L"next", control.IsNextEnabled());
        flag(result, L"previous", control.IsPreviousEnabled());
        return result;
    }

    Media trackMetadata(StorageFile const& source, uint32_t coverSize) {
        auto props = source.Properties().GetMusicPropertiesAsync().get();
        Media next{
            props.Title().empty() ? source.DisplayName() : props.Title(),
            props.Artist().empty() ? props.AlbumArtist() : props.Artist(),
            props.Album(),
            {},
            duration_cast<milliseconds>(props.Duration()).count()
        };
        if (coverSize > 0) {
            try { next.cover = artwork(source.GetThumbnailAsync(ThumbnailMode::MusicView, coverSize).get()); } catch (...) {}
        }
        return next;
    }

    JsonObject openWinRt(JsonObject const& request) {
        auto source = StorageFile::GetFileFromPathAsync(request.GetNamedString(L"path")).get();
        auto next = trackMetadata(source, 640);
        auto item = Windows::Media::Core::MediaSource::CreateFromStorageFile(source);
        if (!local) {
            local = MediaPlayer();
            local.CommandManager().IsEnabled(false);
        }
        if (legacyLocal) { mci(L"close spicy_audio"); legacyLocal = false; }
        local.Pause();
        local.Source(item);
        file = std::move(next);
        local.Play();
        auto result = poll(request);
        if (result.GetNamedNumber(L"duration", 0) == 0) num(result, L"duration", file.duration);
        return result;
    }

    JsonObject open(JsonObject const& request) {
        try { return openWinRt(request); }
        catch (hresult_error const&) {
            auto path = std::filesystem::path(std::wstring(request.GetNamedString(L"path")));
            if (!std::filesystem::is_regular_file(path) || path.wstring().find(L'"') != std::wstring::npos) {
                throw hresult_error(E_INVALIDARG, L"Choose an audio file on this computer.");
            }
            if (legacyLocal) { mci(L"close spicy_audio"); legacyLocal = false; }
            if (local) local.Pause();
            mci(L"open \"" + path.wstring() + L"\" alias spicy_audio");
            try {
                mci(L"set spicy_audio time format milliseconds");
                file = {hstring(path.stem().wstring()), {}, {}, {}, std::stoll(mci(L"status spicy_audio length"))};
                mci(L"play spicy_audio");
                legacyLocal = true;
                return pollLegacy(false);
            } catch (...) {
                try { mci(L"close spicy_audio"); } catch (...) {}
                legacyLocal = false;
                throw;
            }
        }
    }

    JsonObject control(JsonObject const& request) {
        auto id = request.GetNamedString(L"session", L"");
        auto action = request.GetNamedString(L"action");
        bool accepted = false;
        auto target = milliseconds(static_cast<long long>(request.GetNamedNumber(L"position", 0)));
        if (id == L"local" && legacyLocal) {
            if (action == L"toggle" || action == L"pause") {
                auto state = mci(L"status spicy_audio mode");
                if (state == L"playing" || action == L"pause") mci(L"pause spicy_audio");
                else mci(L"play spicy_audio");
                accepted = true;
            } else if (action == L"seek") {
                auto playing = mci(L"status spicy_audio mode") == L"playing";
                mci(L"seek spicy_audio to " + std::to_wstring(target.count()));
                if (playing) mci(L"play spicy_audio");
                accepted = true;
            }
        } else if (id == L"local" && local) {
            if (action == L"toggle") {
                if (local.PlaybackSession().PlaybackState() == MediaPlaybackState::Playing) local.Pause();
                else local.Play();
                accepted = true;
            } else if (action == L"seek" && local.PlaybackSession().CanSeek()) {
                local.PlaybackSession().Position(target);
                accepted = true;
            } else if (action == L"pause") {
                local.Pause();
                accepted = true;
            }
        } else if (auto session = select(id)) {
            if (action == L"toggle") {
                auto info = session.GetPlaybackInfo();
                if (info.Controls().IsPlayPauseToggleEnabled()) accepted = session.TryTogglePlayPauseAsync().get();
                else if (info.PlaybackStatus() == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing)
                    accepted = session.TryPauseAsync().get();
                else accepted = session.TryPlayAsync().get();
            } else if (action == L"next") accepted = session.TrySkipNextAsync().get();
            else if (action == L"previous") accepted = session.TrySkipPreviousAsync().get();
            else if (action == L"seek") {
                auto from = session.GetTimelineProperties().StartTime();
                accepted = session.TryChangePlaybackPositionAsync((from + duration_cast<Windows::Foundation::TimeSpan>(target)).count()).get();
            }
        }
        JsonObject result;
        flag(result, L"accepted", accepted);
        return result;
    }

public:
    JsonObject run(JsonObject const& request) {
        auto command = request.GetNamedString(L"cmd", L"");
        if (command == L"open") return open(request);
        if (command == L"describe") {
            auto source = StorageFile::GetFileFromPathAsync(request.GetNamedString(L"path")).get();
            JsonObject result;
            metadata(result, trackMetadata(source, 128), true);
            return result;
        }
        if (request.GetNamedString(L"session", L"") != L"local" && !manager) {
            manager = Manager::RequestAsync().get();
        }
        if (command == L"poll") return poll(request);
        if (command == L"control") return control(request);
        JsonObject result;
        JsonArray sessions;
        if (manager) {
            for (auto const& session : manager.GetSessions()) {
                auto info = session.GetPlaybackInfo();
                JsonObject value;
                str(value, L"id", session.SourceAppUserModelId());
                flag(value, L"playing", info.PlaybackStatus() == GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing);
                sessions.Append(value);
            }
        }
        result.SetNamedValue(L"sessions", sessions);
        return result;
    }
};

int main() {
    init_apartment(apartment_type::multi_threaded);
    Bridge bridge;
    for (std::string line; std::getline(std::cin, line);) {
        JsonObject result;
        try {
            result = bridge.run(JsonObject::Parse(to_hstring(line)));
        } catch (hresult_error const& error) {
            str(result, L"error", error.message());
        } catch (std::exception const& error) {
            str(result, L"error", to_hstring(error.what()));
        }
        std::cout << to_string(result.Stringify()) << std::endl;
    }
}
