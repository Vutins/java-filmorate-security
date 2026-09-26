package ru.yandex.practicum.filmorate.dao.repositories;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import ru.yandex.practicum.filmorate.exception.InternalServerException;
import ru.yandex.practicum.filmorate.exception.NotFoundException;
import ru.yandex.practicum.filmorate.exception.ValidationException;
import ru.yandex.practicum.filmorate.model.Director;
import ru.yandex.practicum.filmorate.model.Film;
import ru.yandex.practicum.filmorate.model.Genre;
import ru.yandex.practicum.filmorate.model.Rating;
import ru.yandex.practicum.filmorate.storage.FilmStorage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

@Slf4j
@Repository
public class FilmDbStorage implements FilmStorage {

    private static final String PROGRAM_LEVEL = "FilmDbStorage";
    private final JdbcOperations jdbc;
    private final RowMapper<Film> mapper;

    @Autowired
    public FilmDbStorage(final JdbcOperations jdbc, final RowMapper<Film> mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public boolean delete(Long filmId) {
        final String DELETE_FILM_QUERY = """
                DELETE
                FROM films
                WHERE id = ?;
                """;
        return jdbc.update(DELETE_FILM_QUERY, filmId) > 0;
    }

    @Override
    public List<Film> getAllFilms() {
        final String FIND_ALL_FILMS_WITH_MPA_RATING_QUERY = """
                SELECT f.*, mr.name AS mpa_rating_name
                FROM films AS f
                LEFT OUTER JOIN mpa_rating AS mr
                ON f.mpa_rating_id = mr.mpa_rating_id
                """;
        final String FIND_ALL_FILMS_IDS_WITH_GENRES_QUERY = """
                SELECT fg.film_id, fg.genre_id, g.name AS genre_name
                FROM film_genre AS fg
                LEFT OUTER JOIN genres AS g ON fg.genre_id = g.genre_id
                """;

        List<Film> tmpFilms = jdbc.query(FIND_ALL_FILMS_WITH_MPA_RATING_QUERY, mapper);
        if (tmpFilms == null || tmpFilms.isEmpty()) {
            return List.of();
        }

        Map<Long, Set<Genre>> filmsGenres = jdbc.query(FIND_ALL_FILMS_IDS_WITH_GENRES_QUERY, new FilmDbStorage.FilmsIdsWithGenresExtractor());


        List<Film> films = new ArrayList<>();
        for (Film tmpFilm : tmpFilms) {
            if (filmsGenres.containsKey(tmpFilm.getId())) {
                Film film = new Film(tmpFilm.getId(), tmpFilm.getName(), tmpFilm.getDescription(), tmpFilm.getReleaseDate(), tmpFilm.getDuration(), filmsGenres.get(tmpFilm.getId()), tmpFilm.getMpa(), tmpFilm.getDirectors());
                films.add(film);
            } else {
                films.add(tmpFilm);
            }
        }
        return films;
    }

    @Override
    public Film getFilmById(Long id) {
        final String FIND_FILM_BY_ID_WITH_MPA_AND_GENRES_QUERY = """
                SELECT f.*, mr.name AS mpa_rating_name, fg.genre_id, g.name AS genre_name
                FROM films AS f
                LEFT OUTER JOIN mpa_rating AS mr ON f.mpa_rating_id = mr.mpa_rating_id
                LEFT OUTER JOIN film_genre AS fg ON f.id = fg.film_id
                LEFT OUTER JOIN genres AS g ON fg.genre_id = g.genre_id
                WHERE f.id = ?;
                """;

        Optional<Film> optionalFilm = jdbc.query(FIND_FILM_BY_ID_WITH_MPA_AND_GENRES_QUERY, new FilmWithRatingAndGenresExtractor(), id);

        if (optionalFilm.isEmpty()) {
            log.warn(PROGRAM_LEVEL + ": Не удалось получить объект Film по его ID - не найден в приложении");
            throw new NotFoundException("FilmDbStorage: Фильм c ID: " + id + " не найден");
        }
        return optionalFilm.get();
    }

    @Override
    public Film create(Film film) {
        final String INSERT_FILM_QUERY = """
                INSERT INTO films (name, description, release_date, duration, mpa_rating_id)
                VALUES (?, ?, ?, ?, ?);
                """;
        final String INSERT_FILM_ID_GENRES_IDS_QUERY = """
                INSERT INTO film_genre (film_id, genre_id)
                VALUES (?, ?);
                """;

        Integer mpaRatingId = (film.getMpa() != null) ? film.getMpa().getId() : 1;
        final Object[] params = {film.getName(), film.getDescription(), film.getReleaseDate(), film.getDuration(), mpaRatingId};

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement preparedStatement = connection.prepareStatement(INSERT_FILM_QUERY, Statement.RETURN_GENERATED_KEYS);
            for (int idx = 0; idx < params.length; idx++) {
                preparedStatement.setObject(idx + 1, params[idx]);
            }
            return preparedStatement;
        }, keyHolder);
        Long generatedId = keyHolder.getKeyAs(Long.class);
        if (generatedId == null) {
            throw new InternalServerException(PROGRAM_LEVEL + ": Не удалось сохранить данные Film");
        }

        if (!(film.getGenres().isEmpty())) {
            List<Integer> genresIds = film.getGenres().stream().map(Genre::getId).toList();

            jdbc.batchUpdate(INSERT_FILM_ID_GENRES_IDS_QUERY, new BatchPreparedStatementSetter() {
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    Integer genreId = genresIds.get(i);
                    ps.setLong(1, generatedId);
                    ps.setInt(2, genreId);
                }

                public int getBatchSize() {
                    return genresIds.size();
                }
            });
        }

        return new Film(generatedId, film.getName(), film.getDescription(), film.getReleaseDate(), film.getDuration(), film.getGenres(), film.getMpa(), film.getDirectors());
    }

    @Override
    public Film update(Film film) {
        final String UPDATE_FILM_QUERY = """
                UPDATE films
                SET name = ?, description = ?, release_date = ?, duration = ?, mpa_rating_id = ?
                WHERE id = ?;
                """;
        final String DELETE_GENRES_QUERY = """
                DELETE FROM film_genre
                WHERE film_id = ?;
                """;
        final String INSERT_FILM_ID_GENRES_IDS_QUERY = """
                INSERT INTO film_genre (film_id, genre_id)
                VALUES (?, ?);
                """;
        final Object[] params = {film.getName(), film.getDescription(), film.getReleaseDate(), film.getDuration(), film.getMpa().getId(), film.getId()};

        int updatedRows = jdbc.update(UPDATE_FILM_QUERY, params);
        if (updatedRows == 0) {
            throw new InternalServerException(PROGRAM_LEVEL + ": Не удалось обновить данные Film");
        }

        int deletedRows = jdbc.update(DELETE_GENRES_QUERY, film.getId());
        if (deletedRows == 0) {
            log.info("{}: Не удалось удалить genres у Film с ID: {}", PROGRAM_LEVEL, film.getId());
        }

        if (!(film.getGenres().isEmpty())) {
            List<Integer> genresIds = film.getGenres().stream().map(Genre::getId).toList();

            jdbc.batchUpdate(INSERT_FILM_ID_GENRES_IDS_QUERY, new BatchPreparedStatementSetter() {
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    Integer genreId = genresIds.get(i);
                    ps.setLong(1, film.getId());
                    ps.setInt(2, genreId);
                }

                public int getBatchSize() {
                    return genresIds.size();
                }
            });
        }
        return film;
    }

    @Override
    public void addLike(Long filmId, Long userId) {
        final String INSERT_FILM_LIKE_QUERY = """
                INSERT INTO film_like (film_id, user_id)
                VALUES (?, ?);
                """;
        final Object[] params = {filmId, userId};
        try {
            jdbc.update(INSERT_FILM_LIKE_QUERY, params);
        } catch (DuplicateKeyException ignored) {
            log.info("{}: Попытка поставить повторный like для Film с ID: {}", PROGRAM_LEVEL, filmId);
        }
    }

    @Override
    public void removeLike(Long filmId, Long userId) {
        final String DELETE_FILM_LIKE_QUERY = """
                DELETE FROM film_like
                WHERE film_id = ? AND user_id = ?;
                """;

        int rowsDeleted = jdbc.update(DELETE_FILM_LIKE_QUERY, filmId, userId);
        if (rowsDeleted == 0) {
            log.info("{}: Не удалось удалить like у Film с ID: {}", PROGRAM_LEVEL, filmId);
        }
    }

    @Override
    public List<Film> getTopFilms(int limit, Integer genreId, Integer year) {
        final String COUNT_FILMS_QUERY = "SELECT COUNT(*) FROM films";
        Integer totalFilms = jdbc.queryForObject(COUNT_FILMS_QUERY, Integer.class);

        int actualLimit = (totalFilms != null && totalFilms < limit) ? totalFilms : limit;

        final String GET_POPULAR_FILMS_SORTED_BY_GENRE_YEAR = """
                SELECT f.id, f.name, f.description, f.release_date, f.duration, f.mpa_rating_id, mr.name AS mpa_rating_name, COUNT(DISTINCT fl.user_id) AS likes
                FROM films f
                JOIN mpa_rating mr ON f.mpa_rating_id = mr.mpa_rating_id
                LEFT JOIN film_like fl ON f.id = fl.film_id
                LEFT JOIN film_genre fg ON f.id = fg.film_id
                WHERE (? IS NULL OR YEAR(f.release_date) = ?) AND (? IS NULL OR fg.genre_id = ?)
                GROUP BY f.id, f.name, f.description, f.release_date, f.duration, f.mpa_rating_id, mr.name
                ORDER BY likes DESC
                LIMIT ?
                """;

        try {
            List<Film> sortFilms = jdbc.query(GET_POPULAR_FILMS_SORTED_BY_GENRE_YEAR, new PreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps) throws SQLException {
                    ps.setObject(1, year);
                    ps.setObject(2, year);
                    ps.setObject(3, genreId);
                    ps.setObject(4, genreId);
                    ps.setInt(5, limit);
                }
            }, mapper);
            if (sortFilms == null || sortFilms.isEmpty()) {
                return List.of();
            }

            List<Long> filmsId = sortFilms.stream().filter(Objects::nonNull).map(Film::getId).toList();
            String placeholder = String.join(",", Collections.nCopies(filmsId.size(), "?"));
            final String FIND_GENRES_BY_FILM_IDS = String.format("""
                SELECT fg.film_id, fg.genre_id, g.name
                FROM film_genre fg
                JOIN genres g ON fg.genre_id = g.genre_id
                WHERE fg.film_id IN (%s)
                """, placeholder);

            Map<Long, Set<Genre>> filmsGenres = jdbc.query(FIND_GENRES_BY_FILM_IDS, filmsId.toArray(), new FilmDbStorage.FilmsIdsWithGenresExtractor());

            List<Film> films = new ArrayList<>();
            for (Film sortFilm : sortFilms) {
                Set<Genre> genres = filmsGenres.getOrDefault(sortFilm.getId(), new LinkedHashSet<>());
                Film film = new Film(sortFilm.getId(), sortFilm.getName(), sortFilm.getDescription(), sortFilm.getReleaseDate(), sortFilm.getDuration(), genres, sortFilm.getMpa(), sortFilm.getDirectors());
                films.add(film);
            }
            return films;
        } catch (Exception e) {
            log.error("Ошибка при получении популярных фильмов: {}", e.getMessage());
            return List.of();
        }
    }


    @Override
    public List<Film> getFilmsByIds(List<Long> filmIds) {
        if (filmIds == null || filmIds.isEmpty()) {
            return List.of();
        }

        final String FIND_FILMS_BY_IDS_QUERY = """
                SELECT f.*, mr.name AS mpa_rating_name
                FROM films AS f
                LEFT OUTER JOIN mpa_rating AS mr ON f.mpa_rating_id = mr.mpa_rating_id
                WHERE f.id IN (%s);
                """;

        final String FIND_GENRES_FOR_FILMS_QUERY = """
                SELECT fg.film_id, fg.genre_id, g.name AS genre_name
                FROM film_genre AS fg
                LEFT OUTER JOIN genres AS g ON fg.genre_id = g.genre_id
                WHERE fg.film_id IN (%s);
                """;

        String placeholders = String.join(",", Collections.nCopies(filmIds.size(), "?"));
        // nosemgrep: filmorate-sql-injection-concat -- placeholders are only '?' chars, data passed via parameters
        List<Film> films = jdbc.query(String.format(FIND_FILMS_BY_IDS_QUERY, placeholders), mapper, filmIds.toArray());

        if (films.isEmpty()) {
            return List.of();
        }
        // nosemgrep: filmorate-sql-injection-concat -- placeholders are only '?' chars, data passed via parameters
        Map<Long, Set<Genre>> filmsGenres = jdbc.query(String.format(FIND_GENRES_FOR_FILMS_QUERY, placeholders), new FilmsIdsWithGenresExtractor(), filmIds.toArray());

        List<Film> result = new ArrayList<>();
        for (Film film : films) {
            Set<Genre> genres = filmsGenres.getOrDefault(film.getId(), new LinkedHashSet<>());
            Film filmWithGenres = new Film(film.getId(), film.getName(), film.getDescription(), film.getReleaseDate(), film.getDuration(), genres, film.getMpa(), film.getDirectors());
            result.add(filmWithGenres);
        }

        return result;
    }

    @Override
    public List<Film> getFilmsByDirector(Long directorId, String sortBy) {
        final String sql;

        if ("likes".equals(sortBy)) {
            sql = """
                    SELECT f.*, mr.name AS mpa_rating_name
                    FROM films f
                    JOIN film_directors fd ON f.id = fd.film_id
                    LEFT JOIN mpa_rating mr ON f.mpa_rating_id = mr.mpa_rating_id
                    LEFT JOIN film_like fl ON f.id = fl.film_id
                    WHERE fd.director_id = ?
                    GROUP BY f.id, f.name, f.description, f.release_date, f.duration, f.mpa_rating_id, mr.name
                    ORDER BY COUNT(fl.user_id) DESC
                    """;
        } else if ("year".equals(sortBy)) {
            sql = """
                    SELECT f.*, mr.name AS mpa_rating_name
                    FROM films f
                    JOIN film_directors fd ON f.id = fd.film_id
                    LEFT JOIN mpa_rating mr ON f.mpa_rating_id = mr.mpa_rating_id
                    WHERE fd.director_id = ?
                    ORDER BY f.release_date
                    """;
        } else {
            throw new ValidationException("Неверный параметр сортировки: " + sortBy);
        }

        final String genresQuery = """
                SELECT fg.film_id, fg.genre_id, g.name AS genre_name
                FROM film_genre fg
                JOIN genres g ON fg.genre_id = g.genre_id
                WHERE fg.film_id IN (
                    SELECT film_id FROM film_directors WHERE director_id = ?
                )
                """;

        List<Film> films = jdbc.query(sql, mapper, directorId);
        if (films.isEmpty()) {
            return List.of();
        }

        Map<Long, Set<Genre>> filmsGenres = jdbc.query(genresQuery, new FilmsIdsWithGenresExtractor(), directorId);

        return films.stream().map(f -> new Film(f.getId(), f.getName(), f.getDescription(), f.getReleaseDate(), f.getDuration(), filmsGenres.getOrDefault(f.getId(), new LinkedHashSet<>()), f.getMpa(), f.getDirectors())).toList();
    }

    @Override
    public List<Film> getCommonFilms(Long userId, Long friendId) {
        final String FIND_COMMON_FILMS_WITHOUT_GENRES = """
                SELECT
                    f.id,
                    f.name,
                    f.description,
                    f.release_date,
                    f.duration,
                    f.mpa_rating_id,
                    mr.name AS mpa_rating_name,
                    (SELECT COUNT(*) FROM film_like WHERE film_id = f.id) AS total_likes
                FROM films f
                LEFT JOIN mpa_rating mr ON f.mpa_rating_id = mr.mpa_rating_id
                WHERE EXISTS (
                    SELECT 1 FROM film_like WHERE film_id = f.id AND user_id = ?
                )
                AND EXISTS (
                    SELECT 1 FROM film_like WHERE film_id = f.id AND user_id = ?
                )
                ORDER BY total_likes DESC, f.id;
                """;

        final String FIND_GENRES_FOR_FILMS = """
                SELECT fg.film_id,
                    g.genre_id,
                    g.name AS genre_name
                FROM film_genre fg
                JOIN genres g ON fg.genre_id = g.genre_id
                WHERE fg.film_id IN (
                    SELECT f.id
                    FROM films f
                    WHERE EXISTS (
                        SELECT 1 FROM film_like WHERE film_id = f.id AND user_id = ?
                    )
                    AND EXISTS (
                        SELECT 1 FROM film_like WHERE film_id = f.id AND user_id = ?
                    )
                )
                ORDER BY fg.film_id, g.genre_id;
                """;

        try {
            List<Film> commonFilmsWithoutGenres = jdbc.query(FIND_COMMON_FILMS_WITHOUT_GENRES, mapper, userId, friendId);

            if (commonFilmsWithoutGenres.isEmpty()) {
                return List.of();
            }

            Map<Long, Set<Genre>> filmGenresMap = jdbc.query(FIND_GENRES_FOR_FILMS, new ResultSetExtractor<Map<Long, Set<Genre>>>() {
                @Override
                public Map<Long, Set<Genre>> extractData(ResultSet rs) throws SQLException {
                    Map<Long, Set<Genre>> result = new HashMap<>();
                    while (rs.next()) {
                        Long filmId = rs.getLong("film_id");
                        Genre genre = new Genre(rs.getInt("genre_id"), rs.getString("genre_name"));
                        result.computeIfAbsent(filmId, k -> new LinkedHashSet<>()).add(genre);
                    }
                    return result;
                }
            }, userId, friendId);

            List<Film> result = new ArrayList<>();
            for (Film filmWithoutGenres : commonFilmsWithoutGenres) {
                Set<Director> directors = new HashSet<>();
                Set<Genre> genres = filmGenresMap.getOrDefault(filmWithoutGenres.getId(), new LinkedHashSet<>());

                Film fullFilm = new Film(filmWithoutGenres.getId(), filmWithoutGenres.getName(), filmWithoutGenres.getDescription(), filmWithoutGenres.getReleaseDate(), filmWithoutGenres.getDuration(), genres, filmWithoutGenres.getMpa(), directors);
                result.add(fullFilm);
            }
            return result;

        } catch (RuntimeException e) {
            log.error("Произошла ошибка при чтении из базы данных - поиск общих фильмов", e);
            return List.of();
        }
    }

    @Override
    public List<Film> searchFilms(String query, List<String> by) {
        String searchQuery = query.toLowerCase();

        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (by.contains("title")) {
            conditions.add("LOWER(f.name) LIKE ?");
            params.add("%" + searchQuery + "%");
        }

        if (by.contains("director")) {
            conditions.add("""
                    EXISTS (
                        SELECT 1 FROM film_directors fd
                        JOIN directors d ON fd.director_id = d.director_id
                        WHERE fd.film_id = f.id AND LOWER(d.name) LIKE ?
                    )
                    """);
            params.add("%" + searchQuery + "%");
        }

        if (conditions.isEmpty()) {
            return List.of();
        }

        String whereClause = "(" + String.join(" OR ", conditions) + ")";

        String sql = String.format("""
                SELECT f.id, f.name, f.description, f.release_date, f.duration,
                       f.mpa_rating_id, mr.name AS mpa_rating_name
                FROM films f
                LEFT OUTER JOIN mpa_rating mr ON f.mpa_rating_id = mr.mpa_rating_id
                WHERE %s
                ORDER BY (
                    SELECT COUNT(*) FROM film_like fl WHERE fl.film_id = f.id
                ) DESC, f.id
                """, whereClause);

        List<Film> films = jdbc.query(sql, mapper, params.toArray());

        if (films.isEmpty()) {
            return List.of();
        }

        List<Long> filmIds = films.stream().map(Film::getId).toList();
        Map<Long, Set<Genre>> filmsGenres = getGenresForFilms(filmIds);

        List<Film> result = new ArrayList<>();
        for (Film film : films) {
            Set<Genre> genres = filmsGenres.getOrDefault(film.getId(), new LinkedHashSet<>());
            Film filmWithGenres = new Film(film.getId(), film.getName(), film.getDescription(), film.getReleaseDate(), film.getDuration(), genres, film.getMpa(), film.getDirectors());
            result.add(filmWithGenres);
        }
        return result;
    }

    private Map<Long, Set<Genre>> getGenresForFilms(List<Long> filmIds) {
        if (filmIds.isEmpty()) {
            return Map.of();
        }

        final String FIND_GENRES_FOR_FILMS_QUERY = """
                SELECT fg.film_id, fg.genre_id, g.name AS genre_name
                FROM film_genre fg
                LEFT JOIN genres g ON fg.genre_id = g.genre_id
                WHERE fg.film_id IN (%s)
                ORDER BY fg.film_id, g.genre_id
                """;

        String placeholders = String.join(",", Collections.nCopies(filmIds.size(), "?"));
        // nosemgrep: filmorate-sql-injection-concat -- placeholders are only '?' chars, data passed via parameters
        return jdbc.query(String.format(FIND_GENRES_FOR_FILMS_QUERY, placeholders), new FilmsIdsWithGenresExtractor(), filmIds.toArray());
    }

    private static class FilmWithRatingAndGenresExtractor implements ResultSetExtractor<Optional<Film>> {

        @Override
        public Optional<Film> extractData(ResultSet rs) throws SQLException, DataAccessException {
            Film tmpFilm = null;
            Set<Genre> genres = new LinkedHashSet<>();
            Set<Director> directors = new LinkedHashSet<>();
            while (rs.next()) {
                if (tmpFilm == null) {
                    Integer ratingId = rs.getInt("mpa_rating_id");
                    if (ratingId == 0) {
                        ratingId = null;
                    }
                    Rating mpa = new Rating(ratingId, rs.getString("mpa_rating_name"));
                    tmpFilm = new Film(rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getDate("release_date").toLocalDate(), rs.getInt("duration"), new LinkedHashSet<>(), mpa, directors);
                }
                int genreId = rs.getInt("genre_id");
                String genreName = rs.getString("genre_name");
                if (genreId != 0) {
                    Genre genre = new Genre(genreId, genreName);
                    genres.add(genre);
                }
            }

            if ((tmpFilm != null) && (!genres.isEmpty())) {
                Film film = new Film(tmpFilm.getId(), tmpFilm.getName(), tmpFilm.getDescription(), tmpFilm.getReleaseDate(), tmpFilm.getDuration(), genres, tmpFilm.getMpa(), directors);
                return Optional.of(film);
            }
            return Optional.ofNullable(tmpFilm);
        }
    }

    private static class FilmsIdsWithGenresExtractor implements ResultSetExtractor<Map<Long, Set<Genre>>> {

        @Override
        public Map<Long, Set<Genre>> extractData(ResultSet resultSet) throws SQLException, DataAccessException {
            Map<Long, Set<Genre>> data = new HashMap<>();
            while (resultSet.next()) {
                Long filmId = resultSet.getLong("film_id");

                if (filmId != null) {
                    Genre genre = null;

                    if (resultSet.getObject("genre_id") != null) {
                        genre = Genre.builder()
                                .id(resultSet.getInt("genre_id"))
                                .name(resultSet.getString("name"))
                                .build();
                    }

                    if (!data.containsKey(filmId)) {
                        Set<Genre> genres = new LinkedHashSet<>();

                        if (genre != null) {
                            genres.add(genre);
                        }
                        data.put(filmId, genres);
                    } else if (genre != null) {
                        data.get(filmId).add(genre);
                    }
                }
            }
            return data;
        }
    }
}